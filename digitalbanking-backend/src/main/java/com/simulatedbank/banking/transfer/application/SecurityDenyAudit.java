package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.context.SecurityAuditFallbackLog;
import com.simulatedbank.banking.common.context.SecurityAuditFallbackLog.Action;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Best-effort C3 security-denial audit (TRFC-AU-02). A write failure never changes the deny
 * response; it falls back to a security log plus a metric counter (design/01 §5.2). This is backup
 * evidence, not a replacement for the audit log.
 */
public final class SecurityDenyAudit {
    private final AuditEventPort port;
    private final Counter fallbackCounter;
    private final Clock clock;

    public SecurityDenyAudit(AuditEventPort port, MeterRegistry metrics, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.fallbackCounter = registerFallback(Objects.requireNonNull(metrics, "metrics"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void accessDenied(TransferActor actor, String entityType, String entityId,
                             String reasonCode, String requiredPermission) {
        record(Action.SECURITY_ACCESS_DENIED, new AuditEvent(Action.SECURITY_ACCESS_DENIED.name(), "USER", actor.userId(), role(actor),
                entityType, entityId, "REJECTED", actor.correlationId(), actor.requestId(), clock.instant(),
                Map.of("reasonCode", reasonCode, "requiredPermission", requiredPermission)));
    }

    public void idempotencyConflict(TransferActor actor) {
        record(Action.SECURITY_IDEMPOTENCY_CONFLICT, new AuditEvent(Action.SECURITY_IDEMPOTENCY_CONFLICT.name(), "USER", actor.userId(), role(actor),
                "TRANSFER", null, "REJECTED", actor.correlationId(), actor.requestId(), clock.instant(),
                Map.of("reasonCode", "IDEMPOTENCY_CONFLICT")));
    }

    private void record(Action action, AuditEvent event) {
        try {
            if (port.recordStandalone(event).isPresent()) return;
        } catch (RuntimeException failure) {
            // Provider exception details can contain sensitive data.
        }
        try {
            if (fallbackCounter != null) fallbackCounter.increment();
        } catch (RuntimeException ignored) {
            // A registry failure must not suppress backup logging or the deny response.
        }
        try {
            SecurityAuditFallbackLog.rejected(action, event.actorUserId());
        } catch (RuntimeException ignored) {
            // Logging is best-effort for C3.
        }
    }

    private static Counter registerFallback(MeterRegistry registry) {
        try {
            return registry.counter("transfer.security.deny.fallback");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String role(TransferActor actor) {
        return actor.authorities().stream().filter(a -> a.startsWith("ROLE_")).findFirst()
                .map(a -> a.substring("ROLE_".length())).orElse(null);
    }
}
