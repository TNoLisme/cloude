package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Candidate T1 only. No production bean is registered. The surrounding use case
 * must authenticate, authorize, perform the initial lookup and preflight, and
 * dispatch any challenge outside this transaction. Provider APIs and adapters
 * remain unimplemented; all ports below must join the same primary transaction.
 * The command is constructed only after authentication, authorization, request
 * validation, normalization and hashing of the same payload. The challenge is a
 * UUID {@code challengeRef} snapshot (TRFC-ID-05), never a derived BIGINT: T1 inserts
 * the transfer with a NULL challenge FK, then consumeVerified (TRFC-ID-04) revalidates
 * actor, customer, purpose, hash, key, status and expiry under lock and returns the
 * BIGINT challengeId, which is attached to the transfer before flush/audit/commit.
 */
public final class TransferIntentCandidate {
    private final Ports ports;
    private final TransactionTemplate tx;
    private final TransferTelemetry telemetry;

    public TransferIntentCandidate(PlatformTransactionManager manager, Ports ports) {
        this(manager, ports, TransferTelemetry.DISABLED);
    }

    public TransferIntentCandidate(PlatformTransactionManager manager, Ports ports, TransferTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
        this.ports = Objects.requireNonNull(ports, "ports");
        tx = new TransactionTemplate(Objects.requireNonNull(manager, "transaction manager"));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result create(Command command) {
        Objects.requireNonNull(command, "command");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("T1 must not run inside an outer transaction");
        }
        long started = System.nanoTime();
        Result result;
        try {
            result = Objects.requireNonNull(tx.execute(status -> {
                Result attempted = createUnderGuard(command);
                if (attempted instanceof Created) telemetry.intentAfterCommit();
                return attempted;
            }));
        } catch (DuplicateKeyRace ex) {
            telemetry.attempt(TransferTelemetry.Phase.T1, TransferTelemetry.Attempt.ERROR, started);
            // execute has already rolled back T1. Never read within the failed transaction.
            return Objects.requireNonNull(tx.execute(status -> existingAfterRollback(command)));
        } catch (RuntimeException failure) {
            telemetry.attempt(TransferTelemetry.Phase.T1, TransferTelemetry.Attempt.ERROR, started);
            throw failure;
        }
        telemetry.attempt(TransferTelemetry.Phase.T1, TransferTelemetry.Attempt.RETURNED, started);
        return result;
    }

    private Result createUnderGuard(Command command) {
        TransferIdentity identity = command.identity();
        ports.lockStepUpGuard(identity.customerId());
        Optional<IdempotencyDecision.Existing> found = requireLookup(command.idempotencyKey());
        if (found.isPresent()) {
            return classifyExisting(command, found);
        }
        Assessment assessment = Objects.requireNonNull(ports.evaluateStepUpUnderGuard(command), "assessment");
        if (assessment.requiresChallenge() && command.verifiedChallengeRef() == null) {
            return new ChallengeRequired(assessment.policyVersion());
        }
        UUID challengeRef = assessment.requiresChallenge() ? command.verifiedChallengeRef() : null;
        // The challenge FK stays NULL at insert; it is attached only after a successful consume.
        long transferId = ports.insertPendingAndKey(command);
        if (transferId <= 0) {
            throw new IllegalStateException("Invalid transfer ID from insert");
        }
        ports.reserveForPendingTransfer(command, assessment, transferId);
        if (challengeRef != null) {
            long challengeId = ports.consumeVerified(command, challengeRef);
            if (challengeId <= 0) {
                throw new IllegalStateException("Invalid consumed challenge ID");
            }
            ports.attachChallenge(transferId, challengeId);
        }
        // Flush all business writes before acquiring the audit chain lock.
        ports.flushBusinessWrites();
        // Audit is last; a failure propagates and rolls back every preceding write.
        telemetry.requiredAudit(TransferTelemetry.AuditClass.C1,
                () -> ports.recordCreated(identity, command.amount(), command.correlationId(), transferId));
        return new Created(transferId);
    }

    private Result existingAfterRollback(Command command) {
        Optional<IdempotencyDecision.Existing> found = requireLookup(command.idempotencyKey());
        if (found.isEmpty()) {
            // Concurrent purge or lookup anomaly: do not assume it is safe to create again.
            throw new IllegalStateException("Idempotency record unavailable after key conflict");
        }
        return classifyExisting(command, found);
    }

    private Optional<IdempotencyDecision.Existing> requireLookup(String key) {
        return Objects.requireNonNull(ports.findExistingOnPrimary(key), "lookup result");
    }

    private Existing classifyExisting(Command command, Optional<IdempotencyDecision.Existing> found) {
        return new Existing(IdempotencyDecision.classify(command.identity().initiatedByUserId(),
                command.identity().customerId(), command.requestHash(), found));
    }

    /** Internal seams, not provider-owned APIs or production implementations. */
    public interface Ports {
        void lockStepUpGuard(long customerId);
        Optional<IdempotencyDecision.Existing> findExistingOnPrimary(String key);
        Assessment evaluateStepUpUnderGuard(Command command);
        /** Insert transfer (challenge FK NULL) then key in this transaction; map only TransferPersistence's
         * DuplicateIdempotencyKeyException to DuplicateKeyRace, never other unique violations. */
        long insertPendingAndKey(Command command);
        void reserveForPendingTransfer(Command command, Assessment assessment, long transferId);
        /** TRFC-ID-04: revalidate binding, status and expiry under lock, consume once, return the BIGINT
         * challengeId. Business rejection throws ChallengeRejectedException; DB/system errors propagate. */
        long consumeVerified(Command command, UUID challengeRef);
        /** Set transfer.step_up_challenge_id to the consumed BIGINT before flush/audit; unique is the backstop. */
        void attachChallenge(long transferId, long challengeId);
        /** Flush all business writes and triggers before the final audit phase. */
        void flushBusinessWrites();
        /** Append mandatory C1, joining the transaction. Do not log secrets or raw account data. */
        void recordCreated(TransferIdentity identity, TransferAmount amount, String correlationId, long transferId);
    }

    public record Assessment(boolean requiresChallenge, LocalDate businessDate, String policyVersion) {
        public Assessment {
            Objects.requireNonNull(businessDate, "business date");
            if (policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("Invalid policy version");
            }
        }
    }

    public record Command(TransferIdentity identity, TransferAmount amount, String description,
                          String correlationId, String idempotencyKey, String requestHash,
                          long ttlSeconds, String ttlConfigVersion, UUID verifiedChallengeRef) {
        public Command {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(amount, "amount");
            if (identity.stepUpChallengeId() != null
                    || !RequestContext.isValidCorrelationId(correlationId)
                    || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:~\\-]{1,255}")
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || ttlSeconds < 86400 || ttlConfigVersion == null || ttlConfigVersion.isBlank()
                    || ttlConfigVersion.length() > 128
                    || (description != null && description.codePointCount(0, description.length()) > 255)) {
                throw new IllegalArgumentException("Invalid transfer intent command");
            }
        }
        @Override public String toString() { return "Command[redacted]"; }
    }

    public sealed interface Result permits Created, Existing, ChallengeRequired { }
    public record Created(long transferId) implements Result { }
    public record Existing(IdempotencyDecision decision) implements Result {
        public Existing {
            Objects.requireNonNull(decision, "decision");
            if (decision == IdempotencyDecision.NEW) throw new IllegalArgumentException("Expected an existing record");
        }
    }
    public record ChallengeRequired(String policyVersion) implements Result { }

    /** Only a known idempotency-key unique conflict may be translated to this exception. */
    public static final class DuplicateKeyRace extends RuntimeException {
        public DuplicateKeyRace() { super("Idempotency key already exists"); }
    }

    /** Business rejection from consumeVerified (TRFC-ID-04); rolls back T1, mapped to 409/410 by the caller. */
    public static final class ChallengeRejectedException extends RuntimeException {
        public enum Reason { MISMATCH, EXPIRED }
        private final Reason reason;
        public ChallengeRejectedException(Reason reason) {
            super("Step-up challenge rejected: " + reason);
            this.reason = Objects.requireNonNull(reason, "reason");
        }
        public Reason reason() { return reason; }
    }

    /** Mandatory C1 TRANSFER_CREATED failed; rolls back T1, mapped to 500 AUDIT_WRITE_FAILED by the caller. */
    public static final class AuditWriteException extends RuntimeException {
        public AuditWriteException() { super("Transfer created audit write failed"); }
    }
}
