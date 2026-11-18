package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.*;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Consumer logic only: fake primary reads and ports are not concurrency or integration evidence. */
class TransferPreflightCandidateTest {
    private final TransferRequestHash hasher = new TransferRequestHash(new ObjectMapper(), new JcsCanonicalizer());
    private final FakePorts ports = new FakePorts();
    private final TransferPreflightCandidate candidate = new TransferPreflightCandidate(ports, hasher);
    private final Request request = new Request(2, 1, "key", payload(3, DestinationType.ACCOUNT_ID, "4", "1", ""));
    private final String hash = hash(request);

    @Test
    void existingTerminalReplaysBeforeResolvingAccounts() {
        ports.reads.add(Optional.of(stored(2, 1, hash, IdempotencyStatus.COMPLETED)));
        assertThat(candidate.create(request)).isEqualTo(new Replay(new ReplayResponse(201, "safe body")));
        assertThat(ports.calls).containsExactly("lookup");
    }

    @Test
    void mismatchedActorCustomerOrHashNeverReturnsStoredResponse() {
        for (Stored other : List.of(stored(3, 1, hash, IdempotencyStatus.COMPLETED),
                stored(2, 3, hash, IdempotencyStatus.COMPLETED),
                stored(2, 1, "b".repeat(64), IdempotencyStatus.COMPLETED))) {
            FakePorts isolated = new FakePorts();
            isolated.reads.add(Optional.of(other));
            assertThat(new TransferPreflightCandidate(isolated, hasher).create(request)).isEqualTo(new Conflict());
            assertThat(isolated.calls).containsExactly("lookup");
        }
    }

    @Test
    void rejectedPreflightRereadsAndClassifiesConcurrentWinner() {
        for (IdempotencyStatus status : List.of(IdempotencyStatus.FAILED, IdempotencyStatus.IN_PROGRESS)) {
            FakePorts fake = new FakePorts();
            fake.reads.add(Optional.empty());
            fake.reads.add(Optional.of(stored(2, 1, hash, status)));
            fake.preflight = new Rejected(RejectionReason.ACCOUNT_INACTIVE);
            Result result = new TransferPreflightCandidate(fake, hasher).create(request);
            assertThat(result).isEqualTo(status == IdempotencyStatus.FAILED
                    ? new Replay(new ReplayResponse(422, "safe body")) : new InProgress());
            assertThat(fake.calls).containsExactly("lookup", "preflight", "lookup");
        }
    }

    @Test
    void absentAfterRejectedPreflightReturnsDenialAndReadFailureFailsClosed() {
        ports.reads.add(Optional.empty());
        ports.reads.add(Optional.empty());
        ports.preflight = new Rejected(RejectionReason.INSUFFICIENT_BALANCE);
        assertThat(candidate.create(request)).isEqualTo(new PreflightDenied(RejectionReason.INSUFFICIENT_BALANCE));
        FakePorts failure = new FakePorts();
        failure.reads.add(Optional.empty());
        failure.preflight = new Rejected(RejectionReason.ACCOUNT_NOT_FOUND);
        failure.failRead = 2;
        assertThatThrownBy(() -> new TransferPreflightCandidate(failure, hasher).create(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createdIntentSettlesAfterT1AndConcurrentWinnerIsOnlyReplayed() {
        ports.reads.add(Optional.empty());
        ports.preflight = new Accepted(command(2, 1, "key", hash, 3, 4, "1", ""));
        ports.intent = new TransferIntentCandidate.Created(7);
        assertThat(candidate.create(request)).isEqualTo(new Settled(7, TransferSettlementCandidate.Outcome.completed()));
        assertThat(ports.calls).containsExactly("lookup", "preflight", "T1", "T2");
        FakePorts race = new FakePorts();
        race.reads.add(Optional.empty());
        race.reads.add(Optional.of(stored(2, 1, hash, IdempotencyStatus.IN_PROGRESS)));
        race.preflight = ports.preflight;
        race.intent = new TransferIntentCandidate.Existing(IdempotencyDecision.IN_PROGRESS);
        assertThat(new TransferPreflightCandidate(race, hasher).create(request)).isEqualTo(new InProgress());
        assertThat(race.calls).containsExactly("lookup", "preflight", "T1", "lookup");
    }

    @Test
    void cannotSupplyOrMutateRequestHashAndChangedPayloadConflicts() {
        Payload original = payload(3, DestinationType.ACCOUNT_ID, "4", "1", "  note  ");
        Request normalized = new Request(2, 1, "key", original);
        assertThat(original.description()).isEqualTo("note");
        assertThat(Payload.class.isRecord()).isTrue();
        assertThat(Request.class.getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("requestHash");
        Request changed = new Request(2, 1, "key", payload(3, DestinationType.ACCOUNT_ID, "4", "100", "note"));
        ports.reads.add(Optional.of(stored(2, 1, hash(normalized), IdempotencyStatus.COMPLETED)));
        assertThat(candidate.create(changed)).isEqualTo(new Conflict());
        assertThat(ports.calls).containsExactly("lookup");
    }

    @Test
    void alteredT1SourceDestinationAmountDescriptionActorKeyOrHashAreRejected() {
        List<TransferIntentCandidate.Command> altered = List.of(
                command(3, 1, "key", hash, 3, 4, "1", ""),
                command(2, 9, "key", hash, 3, 4, "1", ""),
                command(2, 1, "other", hash, 3, 4, "1", ""),
                command(2, 1, "key", "b".repeat(64), 3, 4, "1", ""),
                command(2, 1, "key", hash, 5, 4, "1", ""),
                command(2, 1, "key", hash, 3, 5, "1", ""),
                command(2, 1, "key", hash, 3, 4, "100", ""),
                command(2, 1, "key", hash, 3, 4, "1", "other"));
        for (TransferIntentCandidate.Command command : altered) {
            FakePorts fake = new FakePorts();
            fake.reads.add(Optional.empty());
            fake.preflight = new Accepted(command);
            assertThatThrownBy(() -> new TransferPreflightCandidate(fake, hasher).create(request))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fake.calls).containsExactly("lookup", "preflight");
        }
        assertThatThrownBy(() -> new TransferAmount(BigDecimal.ONE, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountNumberUsesTrustedAccountResolutionAndRejectsWrongCommandDestination() {
        Request number = new Request(2, 1, "key", payload(3, DestinationType.ACCOUNT_NUMBER, "0123456789", "1", ""));
        ports.reads.add(Optional.empty());
        ports.preflight = new Accepted(command(2, 1, "key", hash(number), 3, 5, "1", ""));
        assertThatThrownBy(() -> candidate.create(number)).isInstanceOf(IllegalStateException.class);
        assertThat(ports.calls).containsExactly("lookup", "resolve", "preflight");
    }

    @Test
    void unresolvedAccountNumberRereadsKeyBeforeRejectingAndLookupFailurePropagates() {
        Request number = new Request(2, 1, "key", payload(3, DestinationType.ACCOUNT_NUMBER, "0123456789", "1", ""));
        ports.reads.add(Optional.empty());
        ports.reads.add(Optional.of(stored(2, 1, hash(number), IdempotencyStatus.COMPLETED)));
        ports.resolved = OptionalLong.empty();
        assertThat(candidate.create(number)).isEqualTo(new Replay(new ReplayResponse(201, "safe body")));
        assertThat(ports.calls).containsExactly("lookup", "resolve", "lookup");
        FakePorts error = new FakePorts();
        error.reads.add(Optional.empty());
        error.failResolve = true;
        assertThatThrownBy(() -> new TransferPreflightCandidate(error, hasher).create(number))
                .isInstanceOf(IllegalStateException.class);
        assertThat(error.calls).containsExactly("lookup", "resolve");
    }

    @Test
    void unknownCommitPropagatesAndOuterTransactionRejected() {
        ports.reads.add(Optional.empty());
        ports.preflight = new Accepted(command(2, 1, "key", hash, 3, 4, "1", ""));
        ports.intent = new TransferIntentCandidate.Created(7);
        ports.failSettlement = true;
        assertThatThrownBy(() -> candidate.create(request)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> candidate.create(request)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void corruptReplayAndSensitiveStringFormsAreRejectedOrRedacted() {
        assertThatThrownBy(() -> new ReplayResponse(500, "body")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Stored(new IdempotencyDecision.Existing(2, 1, hash, IdempotencyStatus.COMPLETED),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.toString()).doesNotContain("key", hash);
        assertThat(request.payload().toString()).doesNotContain("4");
    }

    private String hash(Request r) {
        Payload p = r.payload();
        return hasher.hash(r.customerId(), p.sourceAccountId(), p.destination(), p.amount(), p.description());
    }

    private static Payload payload(long source, DestinationType type, String destination, String amount, String description) {
        return new Payload(source, new Destination(type, destination), new TransferAmount(new BigDecimal(amount), "VND"), description);
    }

    private static Stored stored(long actor, long customer, String hash, IdempotencyStatus status) {
        boolean terminal = status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED;
        return new Stored(new IdempotencyDecision.Existing(actor, customer, hash, status),
                terminal ? Optional.of(new ReplayResponse(status == IdempotencyStatus.COMPLETED ? 201 : 422, "safe body"))
                        : Optional.empty());
    }

    private static TransferIntentCandidate.Command command(long actor, long customer, String key, String hash,
            long source, long destination, String amount, String description) {
        return new TransferIntentCandidate.Command(new TransferIdentity("R", customer, actor, source, destination, null),
                new TransferAmount(new BigDecimal(amount), "VND"), description, "correlation", key, hash, 86400, "v1", null);
    }

    private static final class FakePorts implements Ports {
        final Queue<Optional<Stored>> reads = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        int readCount;
        int failRead;
        boolean failSettlement;
        boolean failResolve;
        OptionalLong resolved = OptionalLong.of(4);
        Preflight preflight;
        TransferIntentCandidate.Result intent;
        @Override public Optional<Stored> findExistingOnPrimary(String key) {
            calls.add("lookup");
            if (++readCount == failRead) throw new IllegalStateException("primary unavailable");
            return reads.remove();
        }
        @Override public OptionalLong resolveAccountNumber(String accountNumber) {
            calls.add("resolve");
            if (failResolve) throw new IllegalStateException("account unavailable");
            return resolved;
        }
        @Override public Preflight preflight(Request request, String hash, long destinationId) {
            calls.add("preflight"); return preflight;
        }
        @Override public TransferIntentCandidate.Result createIntent(TransferIntentCandidate.Command command) {
            calls.add("T1"); return intent;
        }
        @Override public TransferSettlementCandidate.Outcome settleCreated(long transferId) {
            calls.add("T2");
            if (failSettlement) throw new IllegalStateException("commit unknown");
            return TransferSettlementCandidate.Outcome.completed();
        }
    }
}
