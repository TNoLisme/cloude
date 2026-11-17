package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.common.logging.LogRedaction;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter audit tạm thời cho đến khi module audit triển khai `AuditModuleApi`
 * (design/01 §5.2/§11.1; BE-ID-IMP-13). Semantics giữ đúng hợp đồng `AuthAuditPort`:
 *
 * <ul>
 *   <li>C2 `recordInTransaction`: tham gia TX nghiệp vụ của caller; mọi exception lan truyền
 *       → TX rollback (fail-closed, design/01 §5.2). Hiện chỉ phát metric + security log vì
 *       persistence `audit_log` thuộc module audit chưa có → integration BLOCKED
 *       (DEC-ID-013; mock có kiểm soát theo AGENTS.md).</li>
 *   <li>C3 `recordStandalone`: best-effort — lỗi bị nuốt + fallback security log + metric
 *       thất bại + alert (DD-AUD-19; BE-OBS-02), không đổi response nghiệp vụ.</li>
 * </ul>
 *
 * <p>Không bao giờ log password/OTP/hash/token (design/04 §7.1 Level 4; design/01 §5.6):
 * `detail` đi qua {@link LogRedaction#redactText(String)} phòng thủ, mọi field còn lại là
 * mã enum/id đã mask. Metric: `auth.audit.events{action,class,mode,outcome}` và
 * `auth.audit.failures{action,class}` — label bounded, không userId/correlation.</p>
 */
@Component
public class SecurityLogAuditAdapter implements AuthAuditPort {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT");

    private final MeterRegistry meterRegistry;

    @Autowired
    public SecurityLogAuditAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordInTransaction(AuthAuditEvent event, String correlationId) {
        // TX hiện tại do caller sở hữu; exception (kể cả từ emit) phải lan truyền để rollback TX.
        emit(event, correlationId, "STANDALONE_TX");
    }

    @Override
    public void recordStandalone(AuthAuditEvent event, String correlationId) {
        try {
            emit(event, correlationId, "BEST_EFFORT");
        } catch (RuntimeException failure) {
            // design/01 §5.2: C3 fail không được ảnh hưởng response; security log + metric + alert thay thế.
            counter("auth.audit.failures", "action", event.action().name(),
                    "class", event.auditClass().name());
            LOG.error("SECURITY_AUDIT_WRITE_FAILED action={} correlationId={}", event.action(), correlationId);
        }
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException metricsFailure) {
            // Metric thất bại không được che mất fallback security log.
            LOG.error("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }

    /**
     * Điểm write duy nhất (metric + security log). `protected` để test double mô phỏng
     * persist thật của module audit (design/01 §6.1) khi thay thế adapter này —
     * hợp đồng: exception C2 lan truyền, C3 được nuốt ở recordStandalone.
     */
    protected void emit(AuthAuditEvent event, String correlationId, String mode) {
        counter("auth.audit.events", "action", event.action().name(),
                "class", event.auditClass().name(), "mode", mode, "outcome", "SUCCESS");
        // C3 fail-closed-only fields; không message nội dung nhạy cảm; detail qua redaction trung tâm.
        LOG.info("SECURITY_AUDIT action={} outcome={} actor_user_id={} anonymous_ref={} entity_id={} "
                        + "reason_code={} detail={} correlation_id={}",
                event.action(), event.auditClass(), event.actorUserId(), event.anonymousRef(),
                event.entityId(), event.reasonCode(), LogRedaction.redactText(event.detail()), correlationId);
    }
}
