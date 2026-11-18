package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountBalancePort;
import com.simulatedbank.banking.account.domain.AccountStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conditional debit/credit trên {@code banking.account} cho T2 (BE-ACC-07).
 *
 * <p>Nguồn: {@code design/02} §5.3 bước 6, §7.4, §8.2 (SQL canonical), Appendix A.4;
 * {@code design/05} §7.2; {@code 04} §4.3.1. Dùng native SQL/JdbcTemplate như BE-ACC-06 để kiểm
 * soát chính xác WHERE/RETURNING; không JPQL bulk update (Hibernate session cache và
 * {@code clearAutomatically} dễ che lỗi), không read-modify-write ở Java.</p>
 *
 * <h2>Bất biến</h2>
 * <ul>
 *   <li>Debit trừ **cả** {@code available_balance} và {@code ledger_balance}; chỉ khớp khi
 *       {@code status = 'ACTIVE'} và {@code available_balance >= amount} → không bao giờ âm và
 *       giữ {@code available_balance <= ledger_balance}.</li>
 *   <li>Credit cộng cả hai; chỉ khớp khi {@code status = 'ACTIVE'}.</li>
 *   <li>Mỗi operation là một câu atomic; 0 row không phải lỗi SQL mà là kết quả để service phân
 *       loại. CHECK constraint ({@code ck_account_available_balance_non_negative},
 *       {@code ck_account_balance_within_ledger}, {@code NUMERIC(19,2)}) là chốt cuối: vi phạm
 *       (23514/22003) là system error, transaction bị abort và rollback toàn T2 — không nuốt.</li>
 *   <li>{@code updated_at = now()} trong cùng câu update; không update cột nào khác.</li>
 * </ul>
 *
 * <p>Transaction {@code MANDATORY}: adapter chỉ chạy trong T2 của orchestrator; không tự mở,
 * commit, rollback hay retry. Trước mỗi câu lệnh áp default {@code lock_timeout} 2s nếu caller
 * chưa đặt (dùng chung {@link AccountMoneyLockWait} với BE-ACC-06).</p>
 */
@Repository
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccountBalanceAdapter implements AccountBalancePort {

    private static final String RETURNING_COLUMNS =
            "RETURNING account_id, status, available_balance, ledger_balance";

    private static final RowMapper<BalanceRow> MAPPER = JdbcAccountBalanceAdapter::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcAccountBalanceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BalanceRow> debitIfActiveAndSufficient(long accountId, BigDecimal amount) {
        AccountMoneyLockWait.applyDocumentedDefaultIfUnset(jdbc);
        List<BalanceRow> rows = jdbc.query("""
                UPDATE banking.account
                SET available_balance = available_balance - ?,
                    ledger_balance    = ledger_balance - ?,
                    updated_at        = now()
                WHERE account_id = ?
                  AND status = 'ACTIVE'
                  AND available_balance >= ?
                """ + RETURNING_COLUMNS, MAPPER, amount, amount, accountId, amount);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BalanceRow> creditIfActive(long accountId, BigDecimal amount) {
        AccountMoneyLockWait.applyDocumentedDefaultIfUnset(jdbc);
        List<BalanceRow> rows = jdbc.query("""
                UPDATE banking.account
                SET available_balance = available_balance + ?,
                    ledger_balance    = ledger_balance + ?,
                    updated_at        = now()
                WHERE account_id = ?
                  AND status = 'ACTIVE'
                """ + RETURNING_COLUMNS, MAPPER, amount, amount, accountId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BalanceRow> findByIdForUpdate(long accountId) {
        AccountMoneyLockWait.applyDocumentedDefaultIfUnset(jdbc);
        return jdbc.query("""
                SELECT account_id, status, available_balance, ledger_balance
                FROM banking.account
                WHERE account_id = ?
                FOR UPDATE
                """, MAPPER, accountId).stream().findFirst();
    }

    private static BalanceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BalanceRow(
                rs.getLong("account_id"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("available_balance"),
                rs.getBigDecimal("ledger_balance"));
    }
}
