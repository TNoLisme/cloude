package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountAuditPort;
import com.simulatedbank.banking.common.config.ReadAccessDenied;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Emits safe Account ownership denials for Audit C3, with a local security log and metric
 * (design/01 §5.2/§6.1, DEC-INT-009).
 *
 * <ul>
 *   <li>{@code SECURITY_ACCESS_DENIED} là C3 — best-effort: lỗi bị nuốt + fallback security log +
 *       metric thất bại, KHÔNG bao giờ đổi response 403 hay rollback nghiệp vụ.</li>
 *   <li>Không log số tài khoản/ciphertext/blind index/PII: field của
 *       {@link AccountAuditEvent} đã theo allowlist entity {@code ACCOUNT} (design/01 §5.6).</li>
 * </ul>
 *
 * <p>Metric: {@code account.audit.events{action,outcome}} và {@code account.audit.failures{action}}
 * — label bounded, không accountId/correlationId (BE-OBS-18).</p>
 */
@Component
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class AccountSecurityLogAuditAdapter implements AccountAuditPort {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.account.AUDIT");

    private final MeterRegistry meterRegistry;
    private final CustomerQuery customers;
    private final ApplicationEventPublisher events;

    @Autowired
    public AccountSecurityLogAuditAdapter(MeterRegistry meterRegistry, CustomerQuery customers,
            ApplicationEventPublisher events) {
        this.meterRegistry = meterRegistry;
        this.customers = customers;
        this.events = events;
    }

    @Override
    public void recordStandalone(AccountAuditEvent event, String correlationId) {
        try {
            emit(event, correlationId);
            var actor = customers.findByCustomerId(event.actorCustomerId());
            if (actor.isPresent()) {
                events.publishEvent(new ReadAccessDenied(actor.get().userId(), "CUSTOMER",
                        event.entityType(), event.entityId(), correlationId));
            }
        } catch (RuntimeException failure) {
            counter("account.audit.failures", event.action().name());
            LOG.atError()
                    .addKeyValue("action", event.action().name())
                    .addKeyValue("outcome", "WRITE_FAILED")
                    .addKeyValue("correlation_id", correlationId)
                    .log("SECURITY_AUDIT_WRITE_FAILED");
        }
    }

    /** Điểm emit duy nhất (metric + security log); {@code protected} cho test double mô phỏng lỗi. */
    protected void emit(AccountAuditEvent event, String correlationId) {
        counter("account.audit.events", event.action().name());
        LOG.atInfo()
                .addKeyValue("action", event.action().name())
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("actor_customer_id", event.actorCustomerId())
                .addKeyValue("entity_type", event.entityType())
                .addKeyValue("entity_id", event.entityId())
                .addKeyValue("reason_code", event.reasonCode())
                .addKeyValue("correlation_id", correlationId)
                .log("SECURITY_AUDIT");
    }

    private void counter(String name, String action) {
        try {
            meterRegistry.counter(name, "action", action, "outcome", "REJECTED").increment();
        } catch (RuntimeException metricsFailure) {
            LOG.error("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }
}
