package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Candidate create orchestration; deliberately not a runtime component.
 * Caller authenticates and authorizes the actor. The immutable payload is hashed
 * internally; the Account provider is authoritative for account-number resolution.
 * Provider wiring, PostgreSQL atomicity, challenge dispatch and recovery remain open.
 */
public final class TransferPreflightCandidate {
    private final Ports ports;
    private final TransferRequestHash hasher;

    public TransferPreflightCandidate(Ports ports, TransferRequestHash hasher) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public Result create(Request request) {
        Objects.requireNonNull(request, "request");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer orchestration requires no outer transaction");
        }
        Payload payload = request.payload();
        String requestHash = hasher.hash(request.customerId(), payload.sourceAccountId(),
                payload.destination(), payload.amount(), payload.description());
        Optional<Stored> existing = lookup(request);
        if (existing.isPresent()) return classify(request, requestHash, existing.get());

        OptionalLong resolved = payload.destination().type() == DestinationType.ACCOUNT_ID
                ? OptionalLong.of(Long.parseLong(payload.destination().value()))
                : Objects.requireNonNull(ports.resolveAccountNumber(payload.destination().value()), "account resolution");
        if (resolved.isEmpty()) return rejectAfterRecheck(request, requestHash, RejectionReason.ACCOUNT_NOT_FOUND);
        long destinationId = resolved.getAsLong();
        if (destinationId <= 0) throw new IllegalStateException("Invalid resolved account ID");
        Preflight preflight = Objects.requireNonNull(ports.preflight(request, requestHash, destinationId), "preflight result");
        if (preflight instanceof Rejected rejected) {
            return rejectAfterRecheck(request, requestHash, rejected.reason());
        }
        TransferIntentCandidate.Command command = ((Accepted) preflight).command();
        verifyCommandBinding(request, requestHash, destinationId, command);
        TransferIntentCandidate.Result intent = Objects.requireNonNull(ports.createIntent(command), "T1 result");
        if (intent instanceof TransferIntentCandidate.ChallengeRequired challenge) {
            // Challenge dispatch must occur after the guard transaction has returned.
            return new ChallengeRequired(challenge.policyVersion());
        }
        if (intent instanceof TransferIntentCandidate.Existing) {
            // T1 has ended before looking up a concurrent winner and any replay body.
            Stored winner = lookup(request).orElseThrow(() ->
                    new IllegalStateException("Idempotency record unavailable after T1"));
            return classify(request, requestHash, winner);
        }
        long transferId = ((TransferIntentCandidate.Created) intent).transferId();
        if (transferId <= 0) throw new IllegalStateException("Invalid T1 transfer ID");
        // The settlement adapter must claim a durable fence and return only after T2 commit.
        TransferSettlementCandidate.Outcome outcome = Objects.requireNonNull(ports.settleCreated(transferId), "T2 outcome");
        return new Settled(transferId, outcome);
    }

    private Optional<Stored> lookup(Request request) {
        return Objects.requireNonNull(ports.findExistingOnPrimary(request.idempotencyKey()), "lookup result");
    }

    private Result rejectAfterRecheck(Request request, String requestHash, RejectionReason reason) {
        Optional<Stored> existing = lookup(request);
        return existing.isPresent() ? classify(request, requestHash, existing.get()) : new PreflightDenied(reason);
    }

    private Result classify(Request request, String requestHash, Stored stored) {
        IdempotencyDecision decision = IdempotencyDecision.classify(request.actorId(), request.customerId(),
                requestHash, Optional.of(stored.identity()));
        return switch (decision) {
            case CONFLICT -> new Conflict();
            case IN_PROGRESS -> new InProgress();
            case REPLAY -> new Replay(stored.response().orElseThrow(() ->
                    new IllegalStateException("Terminal replay response unavailable")));
            case NEW -> throw new IllegalStateException("Expected an existing record");
        };
    }

    private static void verifyCommandBinding(Request request, String requestHash, long destinationId,
                                             TransferIntentCandidate.Command command) {
        Objects.requireNonNull(command, "T1 command");
        if (command.identity().initiatedByUserId() != request.actorId()
                || command.identity().customerId() != request.customerId()
                || !command.idempotencyKey().equals(request.idempotencyKey())
                || !command.requestHash().equals(requestHash)
                || command.identity().sourceAccountId() != request.payload().sourceAccountId()
                || command.identity().destinationAccountId() != destinationId
                || !command.amount().equals(request.payload().amount())
                || !Objects.toString(command.description(), "").equals(request.payload().description())) {
            throw new IllegalStateException("Preflight command binding mismatch");
        }
    }

    /** Lookup errors must throw; Optional.empty means a successful primary read found no record. */
    public interface Ports {
        Optional<Stored> findExistingOnPrimary(String key);
        /** Delegate only to Account's trusted number lookup; absence differs from provider errors. */
        OptionalLong resolveAccountNumber(String accountNumber);
        /** Validate ownership/status and build T1 using the exact request, hash and resolved account. */
        Preflight preflight(Request request, String requestHash, long destinationId);
        /** Returns after T1 commit or rollback; errors propagate. */
        TransferIntentCandidate.Result createIntent(TransferIntentCandidate.Command command);
        /** Claim a durable attempt and execute T2; unknown commit and other errors propagate. */
        TransferSettlementCandidate.Outcome settleCreated(long transferId);
    }

    public record Payload(long sourceAccountId, Destination destination, TransferAmount amount, String description) {
        public Payload {
            if (sourceAccountId <= 0) throw new IllegalArgumentException("Invalid source account");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(amount, "amount");
            if (description != null && (description.codePointCount(0, description.length()) > 255
                    || description.codePoints().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Invalid description");
            }
            description = description == null ? "" : description.trim();
        }
        @Override public String toString() { return "Payload[redacted]"; }
    }

    public record Request(long actorId, long customerId, String idempotencyKey, Payload payload) {
        public Request {
            if (actorId <= 0 || customerId <= 0 || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9._:~\\-]{1,255}")) {
                throw new IllegalArgumentException("Invalid transfer request binding");
            }
            Objects.requireNonNull(payload, "payload");
        }
        @Override public String toString() { return "Request[redacted]"; }
    }

    /** Loaded together in a consistent primary snapshot; terminal body must already be safe/masked. */
    public record Stored(IdempotencyDecision.Existing identity, Optional<ReplayResponse> response) {
        public Stored {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(response, "response");
            IdempotencyStatus status = identity.status();
            boolean terminal = status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED;
            if (terminal != response.isPresent()) throw new IllegalArgumentException("Invalid stored response state");
            if (terminal && response.orElseThrow().httpStatus() != (status == IdempotencyStatus.COMPLETED ? 201 : 422)) {
                throw new IllegalArgumentException("Invalid terminal replay status");
            }
        }
        @Override public String toString() { return "Stored[redacted]"; }
    }

    public record ReplayResponse(int httpStatus, String safeBody) {
        public ReplayResponse {
            if ((httpStatus != 201 && httpStatus != 422) || safeBody == null) {
                throw new IllegalArgumentException("Invalid stored replay response");
            }
        }
        @Override public String toString() { return "ReplayResponse[redacted]"; }
    }

    public sealed interface Preflight permits Accepted, Rejected { }
    public record Accepted(TransferIntentCandidate.Command command) implements Preflight {
        public Accepted { Objects.requireNonNull(command, "command"); }
    }
    /** Internal reasons require an explicit API catalog mapping by the eventual adapter. */
    public enum RejectionReason { ACCOUNT_NOT_FOUND, ACCOUNT_INACTIVE, SOURCE_NOT_OWNED, INSUFFICIENT_BALANCE, LIMIT_EXCEEDED }
    public record Rejected(RejectionReason reason) implements Preflight {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    public sealed interface Result permits Conflict, InProgress, Replay, PreflightDenied, ChallengeRequired, Settled { }
    public record Conflict() implements Result { }
    public record InProgress() implements Result { }
    public record Replay(ReplayResponse response) implements Result {
        public Replay { Objects.requireNonNull(response, "response"); }
    }
    public record PreflightDenied(RejectionReason reason) implements Result { }
    public record ChallengeRequired(String policyVersion) implements Result { }
    public record Settled(long transferId, TransferSettlementCandidate.Outcome outcome) implements Result { }
}
