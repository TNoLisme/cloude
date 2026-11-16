package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransferIntentCandidateTest {
    private static final String HASH = "a".repeat(64);
    private final FakePorts ports = new FakePorts();
    private final FakeTransactions transactions = new FakeTransactions(ports);
    private final TransferIntentCandidate candidate = new TransferIntentCandidate(transactions, ports);

    @Test
    void createsReservationAndConsumesChallengeBeforeAuditInOneTransaction() {
        ports.challengeRequired = true;
        assertThat(candidate.create(command(9L))).isEqualTo(new TransferIntentCandidate.Created(7));
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "evaluate", "insert:9", "reserve", "consume", "flush", "audit", "commit");
        assertThat(ports.effects).containsExactly("transfer", "key", "reservation", "challenge", "audit");
    }

    @Test
    void challengeRequiredReturnsWithoutCreatingIntentOrDispatching() {
        ports.challengeRequired = true;
        assertThat(candidate.create(command(null))).isEqualTo(new TransferIntentCandidate.ChallengeRequired("v1"));
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "evaluate", "commit");
    }

    @Test
    void sameKeyFoundUnderGuardIsClassifiedBeforePolicyAndWrites() {
        ports.existing = Optional.of(new IdempotencyDecision.Existing(2, 1, HASH, IdempotencyStatus.COMPLETED));
        assertThat(candidate.create(command(null)))
                .isEqualTo(new TransferIntentCandidate.Existing(IdempotencyDecision.REPLAY));
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "commit");
        assertThat(ports.effects).isEmpty();
    }

    @Test
    void auditFailureRollsBackIntentReservationAndChallenge() {
        ports.challengeRequired = true;
        ports.failAudit = true;
        assertThatThrownBy(() -> candidate.create(command(9L))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("audit", "rollback");
    }

    @Test
    void staleOrMismatchedChallengeRollsBackAllWrites() {
        ports.challengeRequired = true;
        ports.consumedId = 10;
        assertThatThrownBy(() -> candidate.create(command(9L))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback");
        assertThat(ports.calls).doesNotContain("audit");
    }

    @Test
    void reservationFailureRollsBackIntentBeforeChallengeAndAudit() {
        ports.challengeRequired = true;
        ports.failReserve = true;
        assertThatThrownBy(() -> candidate.create(command(9L))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("reserve", "rollback").doesNotContain("consume", "audit");
    }

    @Test
    void challengeConsumptionFailureRollsBackIntentAndReservation() {
        ports.challengeRequired = true;
        ports.failConsume = true;
        assertThatThrownBy(() -> candidate.create(command(9L))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback").doesNotContain("audit");
    }

    @Test
    void uniqueKeyRaceRollsBackBeforeReadingWinnerInNewTransaction() {
        ports.race = true;
        ports.winnerAfterRollback = Optional.of(new IdempotencyDecision.Existing(2, 1, HASH, IdempotencyStatus.RECEIVED));
        assertThat(candidate.create(command(null)))
                .isEqualTo(new TransferIntentCandidate.Existing(IdempotencyDecision.IN_PROGRESS));
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "evaluate", "insert:null", "rollback", "begin", "lookup", "commit");
        assertThat(ports.effects).isEmpty();
    }

    @Test
    void missingWinnerAfterConflictFailsClosedAndCannotCreateAgain() {
        ports.race = true;
        assertThatThrownBy(() -> candidate.create(command(null))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("rollback", "begin", "lookup", "rollback");
    }

    @Test
    void refusesOuterTransactionsAndIgnoresUnneededChallenge() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> candidate.create(command(9L))).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(candidate.create(command(9L))).isEqualTo(new TransferIntentCandidate.Created(7));
        assertThat(ports.calls).contains("insert:null").doesNotContain("consume");
    }

    private static TransferIntentCandidate.Command command(Long challenge) {
        return new TransferIntentCandidate.Command(new TransferIdentity("R", 1, 2, 3, 4, null),
                new TransferAmount(new BigDecimal("1.00"), "VND"), null, "correlation", "key", HASH,
                86400, "ttl-v1", challenge);
    }

    private static final class FakePorts implements TransferIntentCandidate.Ports {
        final List<String> calls = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        Optional<IdempotencyDecision.Existing> existing = Optional.empty();
        Optional<IdempotencyDecision.Existing> winnerAfterRollback = Optional.empty();
        boolean challengeRequired;
        boolean failAudit;
        boolean failReserve;
        boolean failConsume;
        boolean race;
        long consumedId = 9;

        @Override public void lockStepUpGuard(long customerId) { calls.add("guard"); }
        @Override public Optional<IdempotencyDecision.Existing> findExistingOnPrimary(String key) {
            calls.add("lookup");
            return existing;
        }
        @Override public TransferIntentCandidate.Assessment evaluateStepUpUnderGuard(TransferIntentCandidate.Command command) {
            calls.add("evaluate");
            return new TransferIntentCandidate.Assessment(challengeRequired, LocalDate.of(2026, 11, 16), "v1");
        }
        @Override public long insertPendingAndKey(TransferIntentCandidate.Command command, Long challengeId) {
            calls.add("insert:" + challengeId);
            effects.add("transfer");
            if (race) throw new TransferIntentCandidate.DuplicateKeyRace();
            effects.add("key");
            return 7;
        }
        @Override public void reserveForPendingTransfer(TransferIntentCandidate.Command command,
                                                         TransferIntentCandidate.Assessment assessment, long transferId) {
            calls.add("reserve"); effects.add("reservation");
            if (failReserve) throw new IllegalStateException("reservation failed");
        }
        @Override public long consumeVerified(TransferIntentCandidate.Command command, long challengeId) {
            calls.add("consume"); effects.add("challenge");
            if (failConsume) throw new IllegalStateException("challenge failed");
            return consumedId;
        }
        @Override public void flushBusinessWrites() { calls.add("flush"); }
        @Override public void recordCreated(TransferIdentity identity, TransferAmount amount, String correlationId, long transferId) {
            calls.add("audit");
            if (failAudit) throw new IllegalStateException("audit failed");
            effects.add("audit");
        }
    }

    /** Model rollback for orchestration tests; does not simulate PostgreSQL or provider transactions. */
    private static final class FakeTransactions extends AbstractPlatformTransactionManager {
        private final FakePorts ports;
        private List<String> before;
        FakeTransactions(FakePorts ports) { this.ports = ports; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            ports.calls.add("begin"); before = List.copyOf(ports.effects);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { ports.calls.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            ports.calls.add("rollback"); ports.effects.clear(); ports.effects.addAll(before);
            if (ports.race) ports.existing = ports.winnerAfterRollback;
        }
    }
}
