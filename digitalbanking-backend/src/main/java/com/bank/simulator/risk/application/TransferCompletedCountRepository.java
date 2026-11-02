package com.bank.simulator.risk.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class TransferCompletedCountRepository {
    private final JdbcTemplate jdbc;

    public TransferCompletedCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countSince(UUID sourceAccountId, Instant fromInclusive, Instant toInclusive) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfers
                WHERE source_account_id = ?
                  AND status = 'COMPLETED'
                  AND completed_at >= ?
                  AND completed_at <= ?
                """, Integer.class, sourceAccountId, Timestamp.from(fromInclusive), Timestamp.from(toInclusive));
        return count == null ? 0 : count;
    }
}
