package com.bank.simulator.account.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AccountJdbcRepository {
    private final JdbcTemplate jdbc;

    public AccountJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountRow lock(UUID accountId) {
        return queryOne("""
                SELECT id, customer_id, account_number, account_type, status,
                       balance::text AS balance, currency, opened_at
                FROM accounts WHERE id = ? FOR UPDATE
                """, accountId);
    }

    public AccountPair lockPair(UUID accountA, UUID accountB) {
        if (accountA == null || accountB == null || accountA.equals(accountB)) return null;
        List<AccountRow> rows = jdbc.query("""
                SELECT id, customer_id, account_number, account_type, status,
                       balance::text AS balance, currency, opened_at
                FROM accounts
                WHERE id IN (?, ?)
                ORDER BY id ASC
                FOR UPDATE
                """, AccountJdbcRepository::map, accountA, accountB);
        if (rows.size() != 2) return null;
        AccountRow first = rows.get(0);
        AccountRow second = rows.get(1);
        return first.id().equals(accountA) ? new AccountPair(first, second) : new AccountPair(second, first);
    }

    public record AccountPair(AccountRow source, AccountRow destination) {}

    public AccountRow find(UUID accountId) {
        return queryOne("""
                SELECT id, customer_id, account_number, account_type, status,
                       balance::text AS balance, currency, opened_at
                FROM accounts WHERE id = ?
                """, accountId);
    }
    public List<AccountRow> findByCustomer(UUID customerId) {
        return jdbc.query("""
                SELECT id, customer_id, account_number, account_type, status,
                       balance::text AS balance, currency, opened_at
                FROM accounts WHERE customer_id = ? ORDER BY opened_at DESC, id DESC
                """, AccountJdbcRepository::map, customerId);
    }
    public AccountRow findByAccountNumber(String accountNumber) {
        return queryOne("""
                SELECT id, customer_id, account_number, account_type, status,
                       balance::text AS balance, currency, opened_at
                FROM accounts WHERE account_number = ?
                """, accountNumber);
    }

    public void debit(UUID accountId, String amount) {
        int updated = jdbc.update("UPDATE accounts SET balance = balance - CAST(? AS NUMERIC), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND balance >= CAST(? AS NUMERIC)",
                amount, accountId, amount);
        if (updated != 1) throw new IllegalStateException("Insufficient account balance");
    }

    public void credit(UUID accountId, String amount) {
        int updated = jdbc.update("UPDATE accounts SET balance = balance + CAST(? AS NUMERIC), updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                amount, accountId);
        if (updated != 1) throw new IllegalStateException("Account is not available for credit");
    }

    public void updateStatus(UUID accountId, String status, Instant now) {
        jdbc.update("UPDATE accounts SET status = ?, updated_at = ? WHERE id = ?", status, Timestamp.from(now), accountId);
    }

    private AccountRow queryOne(String sql, UUID accountId) {
        List<AccountRow> rows = jdbc.query(sql, AccountJdbcRepository::map, accountId);
        return rows.stream().findFirst().orElse(null);
    }

    private AccountRow queryOne(String sql, String accountNumber) {
        List<AccountRow> rows = jdbc.query(sql, AccountJdbcRepository::map, accountNumber);
        return rows.stream().findFirst().orElse(null);
    }

    private static AccountRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AccountRow(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("account_number"), rs.getString("account_type"), rs.getString("status"),
                rs.getString("balance"), rs.getString("currency"), rs.getTimestamp("opened_at").toInstant());
    }

    public record AccountRow(UUID id, UUID customerId, String accountNumber, String accountType,
                             String status, String balance, String currency, Instant openedAt) {
        public String maskedNumber() {
            return "••••" + accountNumber.substring(accountNumber.length() - 4);
        }
    }
}
