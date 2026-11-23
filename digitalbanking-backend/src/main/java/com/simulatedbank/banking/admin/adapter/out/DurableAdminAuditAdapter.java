package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.application.AdminAuditPort;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BE-ADM-INT-01: production {@link AdminAuditPort} over the durable Audit module
 * ({@code AuditModuleApi}/{@code audit_log}, design/01 §4.3/§7).
 *
 * <p>Contract semantics are unchanged (design/01 §5.2):</p>
 * <ul>
 *   <li>C2 joins the caller transaction through {@code recordInTransaction} ({@code MANDATORY}); a failed
 *       insert rolls the approval transition, provider change and idempotency completion back. The adapter
 *       never swallows that failure.</li>
 *   <li>C3 uses {@code recordStandalone} ({@code REQUIRES_NEW}); a failed/absent write never changes the denial
 *       response but stays visible through {@code admin.audit.failures} and the structured
 *       {@code SECURITY_AUDIT_WRITE_FAILED} fallback log.</li>
 * </ul>
 *
 * <p>Payloads, decision notes, OTPs, challenge references and hashes never reach this adapter; the port's
 * allowlisted event is mapped field-by-field into the Audit v1 event. Metric labels stay bounded
 * ({@code action}, {@code class}, {@code outcome}) — no user, approval, correlation or request ids.</p>
 */
@Component
@ConditionalOnProperty(name = {"app.admin.enabled", "app.audit.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class DurableAdminAuditAdapter implements AdminAuditPort {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.admin.AUDIT");

    private final AuditModuleApi audit;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public DurableAdminAuditAdapter(AuditModuleApi audit, MeterRegistry meterRegistry, Clock clock) {
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public void recordInTransaction(AdminAuditEvent event) {
        try {
            audit.recordInTransaction(toAuditEvent(event));
        } catch (RuntimeException failure) {
            // Fail-closed: the caller transaction must roll back; the metric/log is the operational evidence.
            counter("admin.audit.failures", "action", event.action().name(),
                    "class", event.auditClass().name());
            logWriteFailed(event);
            // Owner-classified failure: keeps the documented catalog code (500, retryable) and rolls back.
            throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED,
                    ErrorCode.AUDIT_WRITE_FAILED.detail(), Map.of(), failure);
        }
        // The durable row is the evidence. The structured mirror must only appear once the transaction really
        // commits (BE-ADM-TST-05): registering in afterCommit prevents a success fact for a rolled-back TX.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
            return;
        }
        publish(event);
    }

    @Override
    public void recordStandalone(AdminAuditEvent event) {
        try {
            Optional<?> written = audit.recordStandalone(toAuditEvent(event));
            if (written.isEmpty()) {
                auditFailure(event);
                return;
            }
            publish(event);
        } catch (RuntimeException failure) {
            auditFailure(event);
        }
    }

    private AuditEvent toAuditEvent(AdminAuditEvent event) {
        return new AuditEvent(
                AuditAction.valueOf(event.action().name()),
                AuditActorType.USER,
                event.actorUserId(),
                event.actorRole(),
                event.entityType(),
                event.entityId(),
                event.beforeState(),
                event.afterState(),
                null,
                null,
                AuditOutcome.valueOf(event.outcome()),
                event.correlationId(),
                event.requestId(),
                null,
                clock.instant());
    }

    private void auditFailure(AdminAuditEvent event) {
        counter("admin.audit.failures", "action", event.action().name(), "class", event.auditClass().name());
        logWriteFailed(event);
    }

    /** Bounded metric + allowlisted structured mirror of a durable audit fact (no payload/secret). */
    private void publish(AdminAuditEvent event) {
        counter("admin.audit.events", "action", event.action().name(),
                "class", event.auditClass().name(), "outcome", event.outcome());
        LOG.atInfo()
                .addKeyValue("action", event.action().name())
                .addKeyValue("audit_class", event.auditClass().name())
                .addKeyValue("outcome", event.outcome())
                .addKeyValue("actor_user_id", event.actorUserId() > 0 ? event.actorUserId() : null)
                .addKeyValue("entity_type", event.entityType())
                .addKeyValue("entity_id", event.entityId())
                .addKeyValue("request_type", event.requestType())
                .addKeyValue("reason_code", event.reasonCode())
                .addKeyValue("correlation_id", event.correlationId())
                .addKeyValue("request_id", event.requestId())
                .log("SECURITY_AUDIT");
    }

    private void logWriteFailed(AdminAuditEvent event) {
        LOG.atError()
                .addKeyValue("action", event.action().name())
                .addKeyValue("audit_class", event.auditClass().name())
                .addKeyValue("outcome", "WRITE_FAILED")
                .addKeyValue("correlation_id", event.correlationId())
                .addKeyValue("request_id", event.requestId())
                .log("SECURITY_AUDIT_WRITE_FAILED");
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException metricsFailure) {
            LOG.error("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }
}
