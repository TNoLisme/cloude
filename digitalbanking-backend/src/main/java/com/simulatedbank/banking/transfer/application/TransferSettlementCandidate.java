package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T2 executor. Every port joins the primary transaction. The money adapter owns recovery from
 * a confirmed credit rejection under a savepoint. Handoff owns claim, retry and commit evidence.
 */
public final class TransferSettlementCandidate {
    private final Ports ports;
    private final ZoneId businessZone;
    private final TransactionTemplate tx;
    private final TransactionTemplate recoveryTx;
    private final TransferTelemetry telemetry;

    public TransferSettlementCandidate(PlatformTransactionManager manager, Ports ports, ZoneId businessZone) {
        this(manager, ports, businessZone, TransferTelemetry.DISABLED);
    }

    public TransferSettlementCandidate(PlatformTransactionManager manager, Ports ports, ZoneId businessZone,
                                       TransferTelemetry telemetry) {
        this(manager, ports, businessZone, telemetry, TransactionDefinition.TIMEOUT_DEFAULT);
    }

    public TransferSettlementCandidate(PlatformTransactionManager manager, Ports ports, ZoneId businessZone,
                                       TransferTelemetry telemetry, int timeoutSeconds) {
        this.telemetry = Objects.requireNonNull(telemetry);
        this.ports = Objects.requireNonNull(ports, "ports");
        this.businessZone = Objects.requireNonNull(businessZone, "business zone");
        tx = new TransactionTemplate(Objects.requireNonNull(manager, "transaction manager"));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(timeoutSeconds);
        recoveryTx = new TransactionTemplate(manager);
        recoveryTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** A result is returned only after commit succeeds; commit failures propagate unchanged. */
    public Outcome settle(long transferId, AttemptFence attempt) {
        if (transferId <= 0) throw new IllegalArgumentException("Invalid settlement identity");
        Objects.requireNonNull(attempt, "attempt fence");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("T2 must not run inside an outer transaction");
        }
        // Read immutable context without locking so the customer guard is always acquired first.
        Context context = Objects.requireNonNull(ports.readContextOnPrimary(transferId), "context");
        if (context.transferId() != transferId) throw new IllegalStateException("Transfer context mismatch");
        try (var scope = bindIntent(context)) {
            long started = System.nanoTime();
            return Objects.requireNonNull(tx.execute(status -> {
                telemetry.joinedSettlementAttempt(started);
                return execute(context, attempt, status);
            }));
        }
    }

    /**
     * Execute in the caller's existing primary transaction after a durable attempt
     * claim has committed. Context must have been read on the primary before the
     * guard; the locked port revalidates it and the claim before any money writes.
     * The caller must establish that recovery is safe and commit itself. A failure
     * marks the shared transaction rollback-only. The returned outcome is provisional
     * until that commit succeeds; an unknown commit outcome must be reconciled.
     */
    public Outcome settleInRecoveryTransaction(Context context, AttemptFence attempt) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(attempt, "attempt fence");
        try (var scope = bindIntent(context)) {
            return Objects.requireNonNull(recoveryTx.execute(status -> {
                telemetry.joinedSettlementAttempt(System.nanoTime());
                return execute(context, attempt, status);
            }));
        }
    }

    private static RequestContextHolder.Scope bindIntent(Context context) {
        if (context.correlationId() == null) return () -> { }; // Legacy candidate port.
        String requestId = RequestContextHolder.current().map(RequestContext::requestId)
                .orElseGet(() -> UUID.randomUUID().toString());
        return RequestContextHolder.bind(new RequestContext(context.correlationId(), requestId, null, null));
    }

    private Outcome execute(Context context, AttemptFence attempt, TransactionStatus status) {
        ports.lockStepUpGuard(context.customerId());
        // Lock and re-read PENDING; both durable epoch and token must match this attempt.
        AttemptFence locked = Objects.requireNonNull(ports.lockPendingAndReadFence(context), "durable fence");
        if (!locked.equals(attempt)) throw new IllegalStateException("Stale transfer attempt");
        ports.lockAccountsAscending(context);
        Instant terminalAt = Objects.requireNonNull(ports.primaryClockTimestamp(), "primary time");
        if (terminalAt.isBefore(context.createdAt())) throw new IllegalStateException("Primary clock moved backwards");
        LocalDate businessDate = terminalAt.atZone(businessZone).toLocalDate();
        ports.lockUsageCustomerThenAccount(context, terminalAt, businessDate);
        Optional<Failure> failure = Objects.requireNonNull(ports.validateLockedState(context, businessDate), "validation");
        FraudResult fraud = new FraudResult(false, List.of());
        if (failure.isEmpty()) {
            fraud = evaluateFraud(context, terminalAt, status);
            if (fraud.blocked()) failure = Optional.of(Failure.FRAUD_BLOCKED);
        }
        if (failure.isEmpty()) {
            failure = ports.moveMoney(context, status);
            if (failure.isEmpty()) ports.postAndVerifyBalancedLedger(context);
        }
        Outcome outcome = failure.map(Outcome::failed).orElseGet(Outcome::completed);
        if (failure.isPresent()) {
            ports.releaseFailedReservation(context, terminalAt, businessDate);
        } else {
            ports.settleCompletedReservation(context, terminalAt, businessDate);
        }
        // Writes transfer status, idempotency safe response + TTL snapshot, and outbox atomically.
        ports.writeTerminalAndOutbox(context, outcome, terminalAt);
        ports.flushBusinessWrites();
        // No further business SQL after audit begins.
        try {
            for (Flag flag : fraud.flags().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList()) {
                telemetry.requiredAudit(TransferTelemetry.AuditClass.C2,
                        () -> ports.recordFraudFlagAudit(context, flag, terminalAt));
            }
            telemetry.requiredAudit(TransferTelemetry.AuditClass.C1,
                    () -> ports.recordTerminalAudit(context, outcome, terminalAt));
        } catch (RuntimeException auditFailure) {
            throw new AuditWriteException(auditFailure);
        }
        ports.readyToCommit();
        telemetry.terminalAfterCommit(outcome);
        return outcome;
    }

    private FraudResult evaluateFraud(Context context, Instant terminalAt, TransactionStatus status) {
        if (!ports.fraudSavepointSupported()) {
            return Objects.requireNonNull(ports.evaluateFraud(context, terminalAt), "fraud result");
        }
        Object savepoint = status.createSavepoint();
        FraudResult result;
        try {
            result = Objects.requireNonNull(ports.evaluateFraud(context, terminalAt), "fraud result");
        } catch (FraudEngineException engineFailure) {
            status.rollbackToSavepoint(savepoint);
            if (status.isRollbackOnly() || !ports.fraudContextUsableAfterRollback() || status.isRollbackOnly()) {
                throw engineFailure;
            }
            status.releaseSavepoint(savepoint);
            // Provider must emit the prescribed safe telemetry without business SQL or sensitive data.
            ports.recordFraudFailOpen();
            return new FraudResult(false, List.of());
        }
        status.releaseSavepoint(savepoint);
        return result;
    }

    /** Credit threw after a successful debit. Only a confirmed full rollback can authorize Tfail. */
    public static final class CreditFailure extends IllegalStateException {
        public CreditFailure(Throwable cause) {
            super("Credit failed after debit", cause);
        }

        public static boolean permitsTfail(Throwable failure) {
            if (!(failure instanceof CreditFailure)) return false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                    String state = sql.getSQLState();
                    return !state.startsWith("08") && !state.startsWith("57") && !state.startsWith("40")
                            && !"55P03".equals(state);
                }
            }
            // JDBC proved savepoint rollback, but the whole T2 was already rollback-only.
            return failure.getCause() instanceof UnexpectedRollbackException;
        }
    }

    /** A required C1 or C2 write failed in T2. The transaction must roll back. */
    public static final class AuditWriteException extends IllegalStateException {
        public AuditWriteException(Throwable cause) {
            super("Transfer settlement audit write failed", cause);
        }
    }

    /** Only failures classified by the fraud provider as engine failures may use fail-open. */
    public static final class FraudEngineException extends RuntimeException {
        public FraudEngineException(String message) { super(message); }
    }

    /** All operations except readContextOnPrimary are mandatory participants in the same T2. */
    public interface Ports {
        Context readContextOnPrimary(long transferId);
        void lockStepUpGuard(long customerId);
        /** Lock the transfer, require PENDING and a usable claimed fence, revalidate the
         * immutable context, and return its durable epoch and token. Hold lock to commit. */
        AttemptFence lockPendingAndReadFence(Context context);
        void lockAccountsAscending(Context context);
        Instant primaryClockTimestamp();
        /** Use the exact terminal instant for both configuration selection and settlement. */
        void lockUsageCustomerThenAccount(Context context, Instant terminalAt, LocalDate businessDate);
        /** Re-check source ownership, account state/currency, balance and limit under lock. */
        Optional<Failure> validateLockedState(Context context, LocalDate businessDate);
        FraudResult evaluateFraud(Context context, Instant terminalAt);
        /** Opt in only after proving savepoint and persistence-context recovery with the provider. */
        default boolean fraudSavepointSupported() { return false; }
        /** Restore/clear provider persistence state and prove the shared transaction remains usable. */
        default boolean fraudContextUsableAfterRollback() { return false; }
        /** Emit safe fail-open telemetry; failures propagate and roll back T2. */
        default void recordFraudFailOpen() { throw new IllegalStateException("Fraud telemetry unavailable"); }
        default Optional<Failure> moveMoney(Context context, TransactionStatus status) {
            if (!debitConditional(context)) return Optional.of(Failure.INSUFFICIENT_BALANCE);
            credit(context);
            return Optional.empty();
        }
        default void readyToCommit() { }
        /** Return false only when debit did not occur and insufficient balance was confirmed. */
        default boolean debitConditional(Context context) {
            throw new UnsupportedOperationException("Money adapter required");
        }
        default void credit(Context context) {
            throw new UnsupportedOperationException("Money adapter required");
        }
        /** Post exactly two entries, verify account/amount/linkage and balance before returning. */
        void postAndVerifyBalancedLedger(Context context);
        /** Settle a single matching pending reservation and increment usage exactly once. */
        void settleCompletedReservation(Context context, Instant terminalAt, LocalDate date);
        /** Release a single matching pending reservation without any usage increment. */
        void releaseFailedReservation(Context context, Instant terminalAt, LocalDate date);
        /** Must use valid terminal TTL snapshot and store replay response atomically with outbox. */
        void writeTerminalAndOutbox(Context context, Outcome outcome, Instant terminalAt);
        void flushBusinessWrites();
        void recordFraudFlagAudit(Context context, Flag flag, Instant terminalAt);
        void recordTerminalAudit(Context context, Outcome outcome, Instant terminalAt);
    }

    /** Terminal adapter contract for using the TRFC-NO-01 consumer mapping in the existing T2. */
    public interface NotificationPorts extends Ports {
        TransferNotifications notifications();

        /** Write terminal status and replay snapshot and return the locked facts for enqueue. */
        TransferNotifications.Facts writeTerminal(Context context, Outcome outcome, Instant terminalAt);

        @Override
        default void writeTerminalAndOutbox(Context context, Outcome outcome, Instant terminalAt) {
            var facts = writeTerminal(context, outcome, terminalAt);
            if (facts.transferId() != context.transferId() || facts.senderCustomerId() != context.customerId()
                    || !facts.correlationId().equals(context.correlationId())) {
                throw new IllegalStateException("Transfer notification facts mismatch");
            }
            notifications().enqueue(facts, outcome);
        }
    }

    /** Immutable values used to select the guard; port revalidates full stored details under lock. */
    public record Context(long transferId, long customerId, Instant createdAt, String correlationId) {
        public Context(long transferId, long customerId, Instant createdAt) {
            this(transferId, customerId, createdAt, null);
        }

        public Context {
            if (transferId <= 0 || customerId <= 0) throw new IllegalArgumentException("Invalid transfer context");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
    public record AttemptFence(long epoch, UUID token) {
        public AttemptFence {
            if (epoch <= 0) throw new IllegalArgumentException("Invalid fence epoch");
            Objects.requireNonNull(token, "fence token");
        }
        @Override public String toString() { return "AttemptFence[redacted]"; }
    }
    public enum Failure { INSUFFICIENT_BALANCE, LIMIT_EXCEEDED, ACCOUNT_INACTIVE, ACCOUNT_NOT_FOUND, FRAUD_BLOCKED, PROCESSING_ERROR }
    public record Outcome(Optional<Failure> failure) {
        public Outcome { Objects.requireNonNull(failure, "failure"); }
        public static Outcome completed() { return new Outcome(Optional.empty()); }
        public static Outcome failed(Failure failure) { return new Outcome(Optional.of(failure)); }
    }
    public record Flag(long id, String ruleCode, int ruleVersion, String severity) {
        public Flag {
            if (id <= 0 || ruleCode == null || ruleCode.isBlank() || ruleVersion <= 0
                    || severity == null || severity.isBlank()) throw new IllegalArgumentException("Invalid fraud flag");
        }
    }
    public record FraudResult(boolean blocked, List<Flag> flags) {
        public FraudResult {
            flags = List.copyOf(flags);
            if (new HashSet<>(flags.stream().map(Flag::id).toList()).size() != flags.size()) {
                throw new IllegalArgumentException("Duplicate fraud flag");
            }
        }
    }
}
