package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local Transfer metrics. Callers supply outcomes from fixed catalogs only. */
public final class TransferTelemetry {
    static final TransferTelemetry DISABLED = new TransferTelemetry();
    private final MeterRegistry registry;

    private TransferTelemetry() {
        registry = null;
    }

    public TransferTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        for (AuditClass auditClass : AuditClass.values()) {
            observation(() -> registry.counter("transfer.audit.required.failure", "class", auditClass.name()));
        }
        for (var reason : TransferRecoveryCandidate.Incident.values()) {
            observation(() -> registry.counter("transfer.recovery.incident", "reason", reason.name()));
        }
    }

    void intentAfterCommit() {
        afterCommit(() -> registry.counter("banking.transfer", "status", "CREATED", "failure_code", "NONE").increment());
    }

    void terminalAfterCommit(TransferSettlementCandidate.Outcome outcome) {
        String failure = outcome.failure().map(Enum::name).orElse("NONE");
        afterCommit(() -> registry.counter("banking.transfer", "status",
                outcome.failure().isPresent() ? "FAILED" : "COMPLETED", "failure_code", failure).increment());
    }

    private void afterCommit(Runnable record) {
        if (registry == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { observation(record); }
        });
    }

    enum Phase { T1, T2 }
    enum Attempt { RETURNED, ERROR, COMMITTED, ROLLED_BACK, UNKNOWN }
    enum AuditClass { C1, C2 }
    enum RecoveryResult { RESUMED, RECONCILED, RETRY, MANUAL }

    void attempt(Phase phase, Attempt result, long started) {
        observation(() -> Timer.builder(phase == Phase.T1 ? "transfer.t1.duration" : "transfer.t2.duration")
                .tag("outcome", result.name()).publishPercentileHistogram().register(registry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS));
    }

    void joinedSettlementAttempt(long started) {
        if (registry == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                Attempt result = switch (status) {
                    case STATUS_COMMITTED -> Attempt.COMMITTED;
                    case STATUS_ROLLED_BACK -> Attempt.ROLLED_BACK;
                    default -> Attempt.UNKNOWN;
                };
                attempt(Phase.T2, result, started);
            }
        });
    }

    void idempotency(IdempotencyDecision decision) {
        observation(() -> registry.counter("transfer.idempotency", "outcome", decision.name()).increment());
    }

    void requiredAudit(AuditClass auditClass, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException failure) {
            observation(() -> registry.counter("transfer.audit.required.failure", "class", auditClass.name()).increment());
            throw failure;
        }
    }

    void recovery(RecoveryResult result) {
        observation(() -> registry.counter("transfer.recovery", "outcome", result.name()).increment());
    }

    void recoveryIncident(TransferRecoveryCandidate.Incident reason) {
        observation(() -> registry.counter("transfer.recovery.incident", "reason", reason.name()).increment());
    }

    void purge(int rows) {
        observation(() -> registry.counter("transfer.idempotency.purged").increment(rows));
    }

    private void observation(Runnable record) {
        if (registry == null) return;
        try {
            record.run();
        } catch (RuntimeException ignored) {
            // A broken registry must not alter a transaction or its response.
        }
    }
}
