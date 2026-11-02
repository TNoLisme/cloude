package com.bank.simulator.transfer.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Native SQL row locks for the money path. Lock order is decided by PostgreSQL
 * (ORDER BY id ASC), never by Java UUID.compareTo — see Architecture §5.
 */
@Repository
public class TransferLockRepository {
    private final JdbcTemplate jdbc;

    public TransferLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LockedAccountRow> lockAccountsAscending(UUID accountA, UUID accountB) {
        return jdbc.query("""
                SELECT id, customer_id, balance::text AS balance, currency, status
                FROM accounts
                WHERE id IN (?, ?)
                ORDER BY id ASC
                FOR UPDATE
                """, (rs, row) -> new LockedAccountRow(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("balance"),
                rs.getString("currency"),
                rs.getString("status")), accountA, accountB);
    }

    public record LockedAccountRow(UUID id, UUID customerId, String balance, String currency, String status) {}
}
