package com.bank.simulator.shared.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class IdempotencyJdbcRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotencyRecord find(UUID actorId, String operation, String key) {
        List<IdempotencyRecord> rows = jdbc.query("""
                SELECT id, request_hash, response_status, response_body::text AS response_body,
                       resource_id, expires_at
                FROM idempotency_records
                WHERE actor_id = ? AND operation = ? AND idempotency_key = ? AND expires_at > CURRENT_TIMESTAMP
                """, (rs, row) -> new IdempotencyRecord(rs.getObject("id", UUID.class), rs.getString("request_hash"),
                (Integer) rs.getObject("response_status"), rs.getString("response_body"),
                rs.getObject("resource_id", UUID.class), rs.getTimestamp("expires_at").toInstant()), actorId, operation, key);
        return rows.stream().findFirst().orElse(null);
    }

    public UUID create(UUID actorId, String operation, String key, String requestHash, Instant now, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO idempotency_records (id, actor_id, operation, idempotency_key, request_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, actorId, operation, key, requestHash, Timestamp.from(now), Timestamp.from(expiresAt));
        return id;
    }

    public IdempotencyClaim createOrFind(UUID actorId, String operation, String key, String requestHash,
                                          Instant now, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO idempotency_records (id, actor_id, operation, idempotency_key, request_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class), id, actorId, operation, key, requestHash,
                Timestamp.from(now), Timestamp.from(expiresAt));
        if (!inserted.isEmpty()) {
            return new IdempotencyClaim(new IdempotencyRecord(inserted.getFirst(), requestHash, null, null, null, expiresAt), true);
        }
        IdempotencyRecord existing = find(actorId, operation, key);
        return new IdempotencyClaim(existing, false);
    }

    public record IdempotencyClaim(IdempotencyRecord record, boolean created) {}


    public void complete(UUID id, int responseStatus, String responseBody, UUID resourceId) {
        jdbc.update("""
                UPDATE idempotency_records
                SET response_status = ?, response_body = CAST(? AS JSONB), resource_id = ?
                WHERE id = ?
                """, responseStatus, responseBody, resourceId, id);
    }

    public record IdempotencyRecord(UUID id, String requestHash, Integer responseStatus, String responseBody,
                                    UUID resourceId, Instant expiresAt) {
    }
}
