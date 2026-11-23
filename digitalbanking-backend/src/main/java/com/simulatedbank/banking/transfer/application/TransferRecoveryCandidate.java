package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.transfer.application.TransferTelemetry.AuditClass;
import com.simulatedbank.banking.transfer.application.TransferTelemetry.RecoveryResult;
import com.simulatedbank.banking.transfer.application.TransferRecoveryPersistence.Locked;
import com.simulatedbank.banking.transfer.application.TransferRecoveryPersistence.State;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Basis;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Limit;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Repair;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Replay;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Snapshot;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.CreditFailure;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Primary-only orchestration candidate. Explicit wiring must use the same manager for all JOIN operations. */
public final class TransferRecoveryCandidate {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRecoveryCandidate.class);
    private static final Duration MIN_AGE = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration RETRY_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 100;

    private final TransactionTemplate transaction;
    private final TransferRecoveryPersistence store;
    private final BiConsumer<Context, AttemptFence> settlement;
    private final Ports ports;
    private final ZoneId businessZone;
    private final String owner;
    private final TransferTelemetry telemetry;
    private Context cursor;
    private Instant cutoff;

    public TransferRecoveryCandidate(PlatformTransactionManager manager, TransferRecoveryPersistence store,
                                     TransferSettlementCandidate settlement, Ports ports, ZoneId businessZone,
                                     String owner) {
        this(manager, store, settlement::settleInRecoveryTransaction, ports, businessZone, owner, TransferTelemetry.DISABLED);
    }

    public TransferRecoveryCandidate(PlatformTransactionManager manager, TransferRecoveryPersistence store,
                                     BiConsumer<Context, AttemptFence> settlement, Ports ports, ZoneId businessZone,
                                     String owner, TransferTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        this.store = Objects.requireNonNull(store);
        this.settlement = Objects.requireNonNull(settlement);
        this.ports = Objects.requireNonNull(ports);
        this.businessZone = Objects.requireNonNull(businessZone);
        if (owner == null || !owner.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Transfer recovery worker identifier");
        }
        this.owner = owner;
    }

    public synchronized void recoverBatch() {
        requireJobContext();
        List<Context> candidates;
        try {
            if (cursor == null) cutoff = store.now().minus(MIN_AGE);
            candidates = store.discover(cutoff, cursor, BATCH);
        } catch (RuntimeException failure) {
            telemetry.recoveryIncident(Incident.SCAN_UNAVAILABLE);
            throw failure;
        }
        for (Context context : candidates) {
            if (!RequestContext.isValidCorrelationId(context.correlationId())) {
                alert(context, Incident.CONTEXT_INVALID);
                continue;
            }
            try (var scope = RequestContextHolder.bind(new RequestContext(
                    context.correlationId(), UUID.randomUUID().toString(), null, null))) {
                recover(context);
            }
        }
        cursor = candidates.size() == BATCH ? candidates.getLast() : null;
    }

    public int purgeBatch() {
        requireJobContext();
        int purged = Objects.requireNonNull(transaction.execute(status -> store.purge(BATCH)));
        telemetry.purge(purged);
        return purged;
    }

    /**
     * Entry for the authorized direct T2 caller after T1 commit. The caller must pass the resulting
     * fence to T2, whose locked port must use the same store and customer guard. HTTP retry never calls this.
     */
    public Optional<AttemptFence> claimDirect(Context context) {
        requireNoOuterTransaction();
        return Objects.requireNonNull(transaction.execute(status -> locked(context)
                .filter(row -> row.state() == State.IDLE && row.epoch() == 0 && row.count() == 0)
                .map(row -> store.claim(row, owner, store.now().plus(LEASE), 0, null, null))));
    }

    private void recover(Context context) {
        AttemptFence fence;
        try {
            fence = transaction.execute(status -> claim(context));
        } catch (RuntimeException failure) {
            alert(context, Incident.CLAIM_UNCERTAIN);
            return;
        }
        if (fence == null) return;

        ProcessingCompletion completion = new ProcessingCompletion();
        try {
            transaction.executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(completion);
                process(context, fence, completion);
            });
            if (completion.status != TransactionSynchronization.STATUS_COMMITTED) {
                handleFailedProcessing(context, fence, Incident.OUTCOME_UNCERTAIN, false);
            }
        } catch (UnsafeEvidence failure) {
            handleFailedProcessing(context, fence, Incident.EVIDENCE_UNSAFE, false);
        } catch (RuntimeException failure) {
            if (completion.status == TransactionSynchronization.STATUS_ROLLED_BACK && CreditFailure.permitsTfail(failure)) {
                try {
                    failAfterRollback(context, fence);
                } catch (RuntimeException alreadyAlerted) {
                    // Tfail has already applied the fail-closed handling.
                }
                return;
            }
            boolean retry = completion.retryableBodyFailure
                    && completion.status == TransactionSynchronization.STATUS_ROLLED_BACK;
            handleFailedProcessing(context, fence, Incident.OUTCOME_UNCERTAIN, retry);
        }
    }

    private AttemptFence claim(Context context) {
        Locked row = locked(context).orElse(null);
        if (row == null) return null;

        Instant now = store.now();
        if (row.state() == State.FAILURE_PENDING) {
            if (row.leaseUntil().isAfter(now) && withinWindow(row, now)) {
                return new AttemptFence(row.epoch(), row.token());
            }
        } else if (row.state() == State.MANUAL || row.intent().createdAt().isAfter(now.minus(MIN_AGE))
                || (row.state() == State.CLAIMED && row.leaseUntil().isAfter(now))) {
            return null;
        }
        if (row.state() != State.IDLE || (row.epoch() > 0 && row.count() == 0)) {
            store.clear(row, State.MANUAL, null);
            afterCommit(() -> telemetry.recovery(RecoveryResult.MANUAL));
            afterCommitAlert(context, Incident.FENCE_UNSAFE);
            return null;
        }
        if (exhausted(row, now)) {
            store.clear(row, State.MANUAL, null);
            afterCommit(() -> telemetry.recovery(RecoveryResult.MANUAL));
            afterCommitAlert(context, Incident.RETRY_EXHAUSTED);
            return null;
        }
        if (row.next() != null && row.next().isAfter(now)) return null;

        int count = row.count() + 1;
        Instant next = count == MAX_ATTEMPTS ? null
                : now.plusSeconds((30L << (count - 1)) + ThreadLocalRandom.current().nextInt(6));
        return store.claim(row, owner, now.plus(LEASE), count, row.first() == null ? now : row.first(), next);
    }

    private void process(Context context, AttemptFence fence, ProcessingCompletion completion) {
        ports.requireSystemRecovery();
        Locked row = lockForProcessing(context, fence);
        if (row.state() == State.FAILURE_PENDING) {
            finishFailure(row, fence);
            return;
        }

        Intent intent = row.intent();
        Basis basis = ports.findTransferBasis(intent).orElseThrow(UnsafeEvidence::new);
        Snapshot evidence = Snapshot.read(ports, intent, basis);
        if (evidence.allowsResume(intent, basis) && ports.hasNoResponse(intent)) {
            try {
                settlement.accept(context, fence);
                afterCommit(() -> telemetry.recovery(RecoveryResult.RESUMED));
            } catch (RuntimeException failure) {
                Throwable cause = failure instanceof CreditFailure || failure instanceof TransferSettlementCandidate.AuditWriteException
                        ? failure.getCause() : failure;
                completion.retryableBodyFailure = cause instanceof ConcurrencyFailureException;
                throw failure;
            }
            return;
        }

        Instant now = store.now();
        Repair repair = evidence.repair(intent, basis, now, businessZone).orElseThrow(UnsafeEvidence::new);
        Replay replay = ports.findSafeReplay(intent).filter(value -> repair.acceptsReplay(intent, value))
                .orElseThrow(UnsafeEvidence::new);
        IdempotencyRetention.Snapshot ttl = Objects.requireNonNull(ports.terminalRetention());
        repair.reservationToSettle().ifPresent(reservation ->
                ports.settleForReconciliation(intent, reservation, repair.terminalAt(), repair.terminalDate()));
        store.complete(row, replay, now, ttl);
        ports.flushBusinessWrites();
        telemetry.requiredAudit(AuditClass.C1, () -> ports.recordReconciled(intent, now));
        afterCommit(() -> telemetry.recovery(RecoveryResult.RECONCILED));
    }

    /** Caller observed a classified post-debit credit failure and a confirmed rollback of the entire T2. */
    public Optional<Settlement> failAfterRollback(Context context, AttemptFence fence) {
        requireNoOuterTransaction();
        try {
            transaction.executeWithoutResult(status -> {
                Locked row = lockForProcessing(context, fence);
                if (row.state() == State.CLAIMED) store.markFailurePending(row);
            });
            return Optional.ofNullable(transaction.execute(status -> {
                Locked row = lockForProcessing(context, fence);
                return row.state() == State.FAILURE_PENDING ? finishFailure(row, fence) : null;
            }));
        } catch (FenceUnavailable lostFence) {
            quarantine(context, fence);
            return Optional.empty();
        } catch (RuntimeException failure) {
            quarantine(context, fence);
            throw failure;
        }
    }

    public void quarantine(Context context, AttemptFence fence) {
        handleFailedProcessing(context, fence, Incident.OUTCOME_UNCERTAIN, false);
    }

    private Locked lockForProcessing(Context context, AttemptFence fence) {
        ports.lockCustomerGuard(context.customerId());
        Locked row = store.lock(context)
                .filter(candidate -> candidate.matches(context) && candidate.pending() && candidate.matches(fence))
                .orElseThrow(FenceUnavailable::new);
        Instant now = store.now();
        if (!row.leaseUntil().isAfter(now) || !withinWindow(row, now)) throw new FenceUnavailable();
        return row;
    }

    private static boolean withinWindow(Locked row, Instant now) {
        return row.first() == null || row.first().plus(RETRY_WINDOW).isAfter(now);
    }

    private Settlement finishFailure(Locked row, AttemptFence fence) {
        Intent intent = row.intent();
        Basis basis = ports.findTransferBasis(intent).orElseThrow(UnsafeEvidence::new);
        Snapshot evidence = Snapshot.read(ports, intent, basis);
        if (!evidence.allowsResume(intent, basis) || !ports.hasNoResponse(intent)) throw new UnsafeEvidence();
        Settlement result = Objects.requireNonNull(ports.finalizeFailure(intent, fence, basis, store.now()));
        telemetry.terminalAfterCommit(result.outcome());
        return result;
    }

    private void handleFailedProcessing(Context context, AttemptFence fence, Incident incident, boolean retry) {
        try {
            transaction.executeWithoutResult(status -> {
                if (!ports.tryLockCustomerGuard(context.customerId())) {
                    afterCommitAlert(context, incident);
                    return;
                }
                Optional<Locked> candidate = store.lock(context);
                if (candidate.isEmpty()) {
                    afterCommitAlert(context, incident);
                    return;
                }
                Locked row = candidate.get();
                if (!row.matches(context) || !row.pending() || !row.matches(fence)) return;
                if (retry && row.state() == State.CLAIMED && !exhausted(row, store.now())) {
                    store.clear(row, State.IDLE, row.next());
                    afterCommit(() -> telemetry.recovery(RecoveryResult.RETRY));
                } else {
                    store.clear(row, State.MANUAL, null);
                    afterCommit(() -> telemetry.recovery(RecoveryResult.MANUAL));
                    afterCommitAlert(context, retry ? Incident.RETRY_EXHAUSTED : incident);
                }
            });
        } catch (RuntimeException failure) {
            alert(context, incident);
        }
    }

    private Optional<Locked> locked(Context context) {
        if (!ports.tryLockCustomerGuard(context.customerId())) return Optional.empty();
        return store.lock(context).filter(row -> row.matches(context) && row.pending());
    }

    private static boolean exhausted(Locked row, Instant now) {
        return row.count() >= MAX_ATTEMPTS || (row.first() != null && !now.isBefore(row.first().plus(RETRY_WINDOW)));
    }

    private void requireJobContext() {
        requireNoOuterTransaction();
        ports.requireSystemRecovery();
    }

    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer job requires an independent primary transaction");
        }
    }

    private void afterCommitAlert(Context context, Incident incident) {
        afterCommit(() -> alert(context, incident));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private void alert(Context context, Incident incident) {
        telemetry.recoveryIncident(incident);
        try {
            ports.alert(context.transferId(), incident);
        } catch (RuntimeException failure) {
            LOG.error("Transfer recovery alert delivery failed, reason={}", incident);
        }
    }

    public interface Ports extends TransferRecoveryEvidence.Reads {
        /** Check trusted server-side SYSTEM recovery authority. Throw on denial. */
        void requireSystemRecovery();
        /** Join primary transaction, try the TRFC-LI-04 guard without waiting; false only for contention. */
        boolean tryLockCustomerGuard(long customerId);
        /** Join the processing transaction and acquire the same CUSTOMER guard within its lock timeout. */
        void lockCustomerGuard(long customerId);
        Optional<Basis> findTransferBasis(Intent intent);
        Optional<Replay> findSafeReplay(Intent intent);
        boolean hasNoResponse(Intent intent);
        Settlement finalizeFailure(Intent intent, AttemptFence fence, Basis basis, Instant terminalAt);
        IdempotencyRetention.Snapshot terminalRetention();
        /** Join transaction; settle only a matching pending reservation in the original terminal period. */
        void settleForReconciliation(Intent intent, Limit reservation, Instant terminalAt, LocalDate terminalDate);
        void flushBusinessWrites();
        /** TRFC-AU-01, C1 TRANSFER_RECONCILED by SYSTEM, last write in this transaction. */
        void recordReconciled(Intent intent, Instant repairedAt);
        /** Ops channel only, outside the transaction's business work. */
        void alert(long transferId, Incident reason);
    }

    public enum Incident {
        CONTEXT_INVALID, CLAIM_UNCERTAIN, FENCE_UNSAFE, EVIDENCE_UNSAFE, OUTCOME_UNCERTAIN, RETRY_EXHAUSTED, SCAN_UNAVAILABLE
    }

    private static final class UnsafeEvidence extends IllegalStateException { }
    private static final class FenceUnavailable extends IllegalStateException { }

    private static final class ProcessingCompletion implements TransactionSynchronization {
        private int status = STATUS_UNKNOWN;
        private boolean retryableBodyFailure;
        @Override public void afterCompletion(int result) { status = result; }
    }
}
