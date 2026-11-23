package com.simulatedbank.banking.account.adapter.out;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bounded lock wait dùng chung cho money path Account (BE-ACC-06/07).
 *
 * <p>Nguồn: {@code docs-common/design/02. consistency-concurrency.md} §7.10/§11.2 — T2 phải có
 * {@code lock_timeout} hữu hạn để một row bị transaction khác giữ không treo connection vô hạn.
 * Chỉ áp default {@value #DEFAULT_LOCK_TIMEOUT} khi transaction chưa đặt gì
 * ({@code current_setting('lock_timeout')} là {@code 0}/{@code 0ms}); không ghi đè timeout chặt
 * hơn/khác mà orchestrator đã chọn.</p>
 *
 * <p>{@code SET LOCAL} chỉ có hiệu lực trong transaction hiện tại (không đổi pool/session), và
 * timeout vượt bound vẫn là technical failure {@code 55P03} để orchestrator retry toàn T2.</p>
 */
final class AccountMoneyLockWait {

    /** {@code design/02} §7.10: {@code lock_timeout} T2 = 2s. */
    static final String DEFAULT_LOCK_TIMEOUT = "2s";

    private AccountMoneyLockWait() {
    }

    static void applyDocumentedDefaultIfUnset(JdbcTemplate jdbc) {
        String configured = jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        if (configured == null || "0".equals(configured) || "0ms".equals(configured)) {
            jdbc.execute("SET LOCAL lock_timeout = '" + DEFAULT_LOCK_TIMEOUT + "'");
        }
    }
}
