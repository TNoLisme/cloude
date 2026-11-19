package com.simulatedbank.banking.account.application;

/**
 * Port audit của Account theo {@code design/01. audit-traceability.md} §5.2/§5.3/§5.6.
 *
 * <p>Hôm nay Account chỉ phát {@code SECURITY_ACCESS_DENIED} (C3) khi owner mismatch trên đường
 * đọc (AC-024). {@code ACCOUNT_STATUS_CHANGED} (C2) thuộc status transition — chưa được duyệt
 * (ACC-DR-12), nên không có operation C2 trong port này.</p>
 *
 * <p>Adapter phát denied read event cho Audit C3 theo DEC-INT-009 và ghi log/metric cục bộ.</p>
 */
public interface AccountAuditPort {

    enum AccountAction {
        SECURITY_ACCESS_DENIED
    }

    /**
     * Sự kiện an toàn khi log: chỉ enum/ID/reasonCode thuộc allowlist entity {@code ACCOUNT}
     * (design/01 §5.6: {@code accountId}, {@code customerId}, {@code status}, {@code last4},
     * balance, {@code reasonCode}); KHÔNG có số tài khoản đầy đủ, ciphertext, blind index hay PII
     * Customer.
     *
     * @param actorCustomerId customer của principal đã yêu cầu truy cập (server-side mapping),
     *                        không phải giá trị client gửi
     */
    record AccountAuditEvent(
            AccountAction action,
            long actorCustomerId,
            String entityType,
            String entityId,
            String reasonCode
    ) {
        public AccountAuditEvent {
            if (action == null || entityType == null || entityId == null || reasonCode == null) {
                throw new IllegalArgumentException("account audit event fields are required");
            }
            if (actorCustomerId <= 0) {
                throw new IllegalArgumentException("actorCustomerId must be positive");
            }
        }

        /** Owner mismatch trên account đã tồn tại (403 {@code AUTH_FORBIDDEN}). */
        public static AccountAuditEvent accessDenied(long actorCustomerId, long accountId) {
            return new AccountAuditEvent(
                    AccountAction.SECURITY_ACCESS_DENIED,
                    actorCustomerId,
                    "ACCOUNT",
                    Long.toString(accountId),
                    "AUTH_FORBIDDEN");
        }

        @Override
        public String toString() {
            return "AccountAuditEvent[" + action + "]";
        }
    }

    /** C3: standalone best-effort; lỗi bị nuốt + fallback security log (không đổi response 403). */
    void recordStandalone(AccountAuditEvent event, String correlationId);
}
