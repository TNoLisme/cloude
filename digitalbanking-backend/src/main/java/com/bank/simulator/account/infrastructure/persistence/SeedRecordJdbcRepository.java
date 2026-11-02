package com.bank.simulator.account.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class SeedRecordJdbcRepository {
    private final JdbcTemplate jdbc;

    public SeedRecordJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SeedRecord insert(UUID accountId, String amount, String currency, UUID actorId,
                             String reference, UUID idempotencyRecordId, Instant now) {
        UUID seedId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_seed_records
                    (id, account_id, amount, currency, actor_id, reference, idempotency_record_id, created_at)
                VALUES (?, ?, CAST(? AS NUMERIC), ?, ?, ?, ?, ?)
                """, seedId, accountId, amount, currency, actorId, reference, idempotencyRecordId, Timestamp.from(now));
        return new SeedRecord(seedId, accountId, amount, currency, now);
    }

    public record SeedRecord(UUID seedTransactionId, UUID accountId, String amount, String currency, Instant createdAt) {
    }
}
