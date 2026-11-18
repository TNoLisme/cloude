package com.simulatedbank.banking.transfer.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort C3 security-denial audit (TRFC-AU-02). A write failure never changes the deny
 * response; it falls back to a security log plus a metric counter (design/01 §5.2). This is backup
 * evidence, not a replacement for the audit log.
 */
public final class SecurityDenyAudit {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityDenyAudit.class);
    private final AuditEventPort port;
    private final MeterRegistry metrics;
    private final Clock clock;

    public SecurityDenyAudit(AuditEventPort port, MeterRegistry metrics, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void accessDenied(TransferActor actor, String entityType, String entityId,
                             String reasonCode, String requiredPermission) {
        record(new AuditEvent("SECURITY_ACCESS_DENIED", "USER", actor.userId(), role(actor),
                entityType, entityId, "REJECTED", actor.correlationId(), actor.requestId(), clock.instant(),
                Map.of("reasonCode", reasonCode, "requiredPermission", requiredPermission)));
    }

    public void idempotencyConflict(TransferActor actor) {
        record(new AuditEvent("SECURITY_IDEMPOTENCY_CONFLICT", "USER", actor.userId(), role(actor),
                "TRANSFER", null, "REJECTED", actor.correlationId(), actor.requestId(), clock.instant(),
                Map.of("reasonCode", "IDEMPOTENCY_CONFLICT")));
    }

    private void record(AuditEvent event) {
        try {
            port.recordStandalone(event);
        } catch (RuntimeException failure) {
            // Backup evidence only; the deny decision stands. No raw key/body/PII is logged.
            metrics.counter("transfer.security.deny.fallback").increment();
            LOG.warn("Security audit fallback action={} outcome={} correlationId={}",
                    event.action(), event.outcome(), event.correlationId());
        }
    }

    private static String role(TransferActor actor) {
        return actor.authorities().stream().filter(a -> a.startsWith("ROLE_")).findFirst()
                .map(a -> a.substring("ROLE_".length())).orElse(null);
    }
}