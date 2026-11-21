package com.simulatedbank.banking.customer.application;

import org.springframework.lang.Nullable;

/**
 * Port audit của Customer theo `design/01. audit-traceability.md` §5.2/§5.3: action duy nhất
 * Customer phát hôm nay là `SECURITY_ACCESS_DENIED` (C3 — `REJECTED`, standalone best-effort,
 * "Standalone sau khi trả 403"). Truy cập staff thành công KHÔNG phát action riêng: catalog
 * design/01 §5.7 quy định view/search thành công chỉ ghi technical access log
 * ({@code HttpRequestLog}) để tránh write amplification.
 *
 * <p>Adapter phát denied read event cho Audit C3 theo DEC-INT-009 và ghi log/metric cục bộ.
 * Payload chỉ chứa metadata an toàn theo allowlist CUSTOMER (design/01 §5.6).</p>
 */
public interface CustomerAuditPort {

    enum CustomerAction {
        SECURITY_ACCESS_DENIED
    }

    /**
     * Sự kiện audit an toàn khi log: chỉ mã enum/ID/status/reasonCode; KHÔNG có PII, token hay
     * ciphertext (design/01 §5.6 allowlist entity `CUSTOMER`).
     */
    record CustomerAuditEvent(CustomerAction action, @Nullable Long actorUserId,
            @Nullable String actorRoleCodes, @Nullable String entityType, @Nullable String entityId,
            @Nullable String reasonCode) {

        /** 403 do thiếu permission/scope khi truy cập hồ sơ customer của staff. */
        public static CustomerAuditEvent accessDenied(long actorUserId, String actorRoleCodes,
                long customerId) {
            return new CustomerAuditEvent(CustomerAction.SECURITY_ACCESS_DENIED, actorUserId,
                    actorRoleCodes, "CUSTOMER", Long.toString(customerId), "AUTH_FORBIDDEN");
        }
    }

    /** C3: standalone best-effort; lỗi bị nuốt + fallback security log (không đổi response 403). */
    void recordStandalone(CustomerAuditEvent event, String correlationId);
}
