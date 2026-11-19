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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransferIntentCandidateTest {
    private static final String HASH = "a".repeat(64);
    private static final UUID CHALLENGE_REF = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private final FakePorts ports = new FakePorts();
    private final FakeTransactions transactions = new FakeTransactions(ports);
    private final TransferIntentCandidate candidate = new TransferIntentCandidate(transactions, ports);

    @Test
    void createsReservationConsumesUuidThenAttachesBigintForeignKeyBeforeAudit() {
        ports.challengeRequired = true;
        assertThat(candidate.create(command(CHALLENGE_REF))).isEqualTo(new TransferIntentCandidate.Created(7));
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "evaluate", "insert", "reserve",
                "consume", "attach:9", "flush", "audit", "commit");
        assertThat(ports.effects).containsExactly("transfer", "key", "reservation", "challenge", "audit");
        assertThat(ports.attachedTransferId).isEqualTo(7);
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
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF)))
                .isInstanceOf(TransferIntentCandidate.AuditWriteException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("audit", "rollback");
    }

    @Test
    void mismatchedChallengeRejectionRollsBackAllWritesBeforeAttachAndAudit() {
        ports.challengeRequired = true;
        ports.challengeRejection = TransferIntentCandidate.ChallengeRejectedException.Reason.MISMATCH;
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF)))
                .isInstanceOf(TransferIntentCandidate.ChallengeRejectedException.class)
                .satisfies(ex -> assertThat(((TransferIntentCandidate.ChallengeRejectedException) ex).reason())
                        .isEqualTo(TransferIntentCandidate.ChallengeRejectedException.Reason.MISMATCH));
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback").doesNotContain("attach:9", "audit");
    }

    @Test
    void expiredChallengeRejectionRollsBackAllWrites() {
        ports.challengeRequired = true;
        ports.challengeRejection = TransferIntentCandidate.ChallengeRejectedException.Reason.EXPIRED;
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF)))
                .isInstanceOf(TransferIntentCandidate.ChallengeRejectedException.class)
                .satisfies(ex -> assertThat(((TransferIntentCandidate.ChallengeRejectedException) ex).reason())
                        .isEqualTo(TransferIntentCandidate.ChallengeRejectedException.Reason.EXPIRED));
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback").doesNotContain("attach:9", "audit");
    }

    @Test
    void nonPositiveConsumedChallengeIdFailsClosedBeforeAttach() {
        ports.challengeRequired = true;
        ports.consumedId = 0;
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback").doesNotContain("attach:0", "audit");
    }

    @Test
    void reservationFailureRollsBackIntentBeforeChallengeAndAudit() {
        ports.challengeRequired = true;
        ports.failReserve = true;
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("reserve", "rollback").doesNotContain("consume", "audit");
    }

    @Test
    void challengeConsumptionFailureRollsBackIntentAndReservation() {
        ports.challengeRequired = true;
        ports.failConsume = true;
        assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF))).isInstanceOf(IllegalStateException.class);
        assertThat(ports.effects).isEmpty();
        assertThat(ports.calls).endsWith("consume", "rollback").doesNotContain("audit");
    }

    @Test
    void uniqueKeyRaceRollsBackBeforeReadingWinnerInNewTransaction() {
        ports.race = true;
        ports.winnerAfterRollback = Optional.of(new IdempotencyDecision.Existing(2, 1, HASH, IdempotencyStatus.RECEIVED));
        assertThat(candidate.create(command(null)))
                .isEqualTo(new TransferIntentCandidate.Existing(IdempotencyDecision.IN_PROGRESS));
        assertThat(ports.calls).containsExactly("begin", "guard", "lookup", "evaluate", "insert", "rollback",
                "begin", "lookup", "commit");
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
            assertThatThrownBy(() -> candidate.create(command(CHALLENGE_REF))).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(candidate.create(command(CHALLENGE_REF))).isEqualTo(new TransferIntentCandidate.Created(7));
        assertThat(ports.calls).contains("insert").doesNotContain("consume", "attach:9");
    }

    @Test
    void commandCannotPreSetTheChallengeForeignKey() {
        assertThatThrownBy(() -> new TransferIntentCandidate.Command(new TransferIdentity("R", 1, 2, 3, 4, 9L),
                new TransferAmount(new BigDecimal("1.00"), "VND"), null, "correlation", "key", HASH,
                CHALLENGE_REF)).isInstanceOf(IllegalArgumentException.class);
    }

    private static TransferIntentCandidate.Command command(UUID challengeRef) {
        return new TransferIntentCandidate.Command(new TransferIdentity("R", 1, 2, 3, 4, null),
                new TransferAmount(new BigDecimal("1.00"), "VND"), null, "correlation", "key", HASH,
                challengeRef);
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
        long attachedTransferId;
        TransferIntentCandidate.ChallengeRejectedException.Reason challengeRejection;

        @Override public void lockStepUpGuard(long customerId) { calls.add("guard"); }
        @Override public Optional<IdempotencyDecision.Existing> findExistingOnPrimary(String key) {
            calls.add("lookup");
            return existing;
        }
        @Override public TransferIntentCandidate.Assessment evaluateStepUpUnderGuard(TransferIntentCandidate.Command command) {
            calls.add("evaluate");
            return new TransferIntentCandidate.Assessment(challengeRequired, LocalDate.of(2026, 11, 16), "v1");
        }
        @Override public long insertPendingAndKey(TransferIntentCandidate.Command command) {
            calls.add("insert");
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
        @Override public long consumeVerified(TransferIntentCandidate.Command command, UUID challengeRef) {
            calls.add("consume"); effects.add("challenge");
            if (failConsume) throw new IllegalStateException("challenge failed");
            if (challengeRejection != null) throw new TransferIntentCandidate.ChallengeRejectedException(challengeRejection);
            return consumedId;
        }
        @Override public void attachChallenge(long transferId, long challengeId) {
            calls.add("attach:" + challengeId); attachedTransferId = transferId;
        }
        @Override public void flushBusinessWrites() { calls.add("flush"); }
        @Override public void recordCreated(TransferIdentity identity, TransferAmount amount, String correlationId, long transferId) {
            calls.add("audit");
            if (failAudit) throw new TransferIntentCandidate.AuditWriteException();
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