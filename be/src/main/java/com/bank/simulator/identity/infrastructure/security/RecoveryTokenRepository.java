package com.bank.simulator.identity.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RecoveryTokenRepository {
    private final JdbcTemplate jdbc;

    public RecoveryTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(UUID userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
    }

    public void create(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO recovery_reset_tokens (id, user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, userId, tokenHash, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public UUID findUserId(String tokenHash) {
        List<UUID> users = jdbc.query("SELECT user_id FROM recovery_reset_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getObject(1, UUID.class), tokenHash);
        return users.stream().findFirst().orElse(null);
    }

    public RecoveryTokenRecord lock(String tokenHash) {
        List<RecoveryTokenRecord> tokens = jdbc.query("""
                SELECT id, user_id, expires_at, consumed_at, invalidated_at
                FROM recovery_reset_tokens WHERE token_hash = ? FOR UPDATE
                """, (rs, row) -> new RecoveryTokenRecord(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()), tokenHash);
        return tokens.stream().findFirst().orElse(null);
    }

    public void consume(UUID id, Instant now) {
        int updated = jdbc.update("""
                UPDATE recovery_reset_tokens SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                """, Timestamp.from(now), id, Timestamp.from(now));
        if (updated != 1) throw new IllegalStateException("Recovery token could not be consumed");
    }

    public void invalidateActiveForUser(UUID userId, Instant now) {
        jdbc.update("""
                UPDATE recovery_reset_tokens SET invalidated_at = ?
                WHERE user_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(now), userId);
    }

    public record RecoveryTokenRecord(UUID id, UUID userId, Instant expiresAt,
                                      Instant consumedAt, Instant invalidatedAt) {}
}
