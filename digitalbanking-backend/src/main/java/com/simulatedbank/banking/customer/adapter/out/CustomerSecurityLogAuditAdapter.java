package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.application.CustomerAuditPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter audit tạm thời cho đến khi module audit triển khai persistence `audit_log`
 * (design/01 §5.2/§6.1). Semantics khớp hợp đồng {@link CustomerAuditPort} và pattern đã duyệt
 * của identity ({@code SecurityLogAuditAdapter}, DEC-ID-003):
 *
 * <ul>
 *   <li>`SECURITY_ACCESS_DENIED` là C3 — best-effort: lỗi bị nuốt + fallback security log +
 *       metric thất bại (alert), KHÔNG bao giờ đổi response 403 hay rollback nghiệp vụ.</li>
 *   <li>Không log PII/token/ciphertext: mọi field của {@link CustomerAuditEvent} đã là mã
 *       enum/ID/reasonCode an toàn theo allowlist design/01 §5.6 (entity `CUSTOMER`).</li>
 * </ul>
 *
 * <p>Metric: `customer.audit.events{action,outcome}` và `customer.audit.failures{action}` —
 * label bounded, không userId/correlation.</p>
 */
@Component
public class CustomerSecurityLogAuditAdapter implements CustomerAuditPort {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.customer.AUDIT");

    private final MeterRegistry meterRegistry;

    @Autowired
    public CustomerSecurityLogAuditAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordStandalone(CustomerAuditEvent event, String correlationId) {
        try {
            emit(event, correlationId);
        } catch (RuntimeException failure) {
            counter("customer.audit.failures", event.action().name());
            LOG.atError()
                    .addKeyValue("action", event.action().name())
                    .addKeyValue("outcome", "WRITE_FAILED")
                    .addKeyValue("correlation_id", correlationId)
                    .log("SECURITY_AUDIT_WRITE_FAILED");
        }
    }

    /** Điểm emit duy nhất (metric + security log); `protected` cho test double mô phỏng persist thật. */
    protected void emit(CustomerAuditEvent event, String correlationId) {
        counter("customer.audit.events", event.action().name());
        LOG.atInfo()
                .addKeyValue("action", event.action().name())
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("actor_user_id", event.actorUserId())
                .addKeyValue("actor_roles", event.actorRoleCodes())
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
