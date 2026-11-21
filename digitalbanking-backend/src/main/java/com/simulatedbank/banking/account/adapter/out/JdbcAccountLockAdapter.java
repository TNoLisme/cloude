package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountLockPort;
import com.simulatedbank.banking.account.domain.AccountStatus;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pessimistic row lock cho T2 của Transfer (BE-ACC-06).
 *
 * <p>Nguồn: {@code design/02} §7.3/§7.4/A.3 và §11.3 — money path dùng native SQL/JdbcTemplate để
 * kiểm soát chính xác {@code ORDER BY}/{@code FOR UPDATE} (Hibernate có thể đổi plan/order của
 * {@code @Lock PESSIMISTIC_WRITE}). SQL chỉ chạm đúng hai row được yêu cầu, khóa theo
 * {@code account_id ASC} trong một câu lệnh (thứ tự khóa toàn cục: transfer → account ASC).</p>
 *
 * <h2>Bounded wait</h2>
 * <p>{@code design/02} §7.10/§11.2 yêu cầu T2 đặt {@code lock_timeout = 2s} để không chờ vô hạn.
 * Adapter tôn trọng cấu hình của caller; chỉ áp default 2s khi transaction chưa đặt gì
 * ({@code current_setting('lock_timeout')} là {@code 0}/{@code 0ms}) — tránh treo connection khi
 * orchestrator quên, và không ghi đè timeout chặt hơn/khác đã được T2 chọn. {@code SET LOCAL}
 * chỉ có hiệu lực trong transaction hiện tại, không ảnh hưởng pool (design/02 §11.2).</p>
 *
 * <p>Lock được PostgreSQL nhả khi transaction kết thúc (commit/rollback); không có cơ chế unlock
 * thủ công, không cache snapshot sau commit. Lỗi {@code 55P03}/{@code 40P01}/{@code 57014} là
 * technical và lan truyền nguyên vẹn.</p>
 */
@Repository
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccountLockAdapter implements AccountLockPort {

    private static final RowMapper<LockedAccountRow> MAPPER = (rs, rowNum) -> new LockedAccountRow(
            rs.getLong("account_id"),
            rs.getLong("customer_id"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getString("currency"),
            rs.getBigDecimal("available_balance"),
            rs.getBigDecimal("ledger_balance"));

    private final JdbcTemplate jdbc;

    public JdbcAccountLockAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LockedAccountRow> lockTwoForUpdateOrderByIdAsc(long firstAccountId, long secondAccountId) {
        AccountMoneyLockWait.applyDocumentedDefaultIfUnset(jdbc);
        return jdbc.query("""
                SELECT account_id, customer_id, status, currency, available_balance, ledger_balance
                FROM banking.account
                WHERE account_id IN (?, ?)
                ORDER BY account_id
                FOR UPDATE
                """, MAPPER, firstAccountId, secondAccountId);
    }
}
