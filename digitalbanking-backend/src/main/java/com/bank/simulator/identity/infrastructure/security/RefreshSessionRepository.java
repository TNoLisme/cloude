package com.bank.simulator.identity.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RefreshSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefreshSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID sessionId, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO refresh_sessions (id, user_id, refresh_token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, userId, tokenHash, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public RefreshSessionRecord findForUpdate(String tokenHash) {
        List<RefreshSessionRecord> rows = jdbcTemplate.query("""
                SELECT id, user_id, refresh_token_hash, expires_at, revoked_at, replaced_by_session_id, created_at
                FROM refresh_sessions
                WHERE refresh_token_hash = ?
                FOR UPDATE
                """, (rs, row) -> new RefreshSessionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("refresh_token_hash"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getObject("replaced_by_session_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), tokenHash);
        return rows.stream().findFirst().orElse(null);
    }

    public List<String> findRoles(UUID userId) {
        return jdbcTemplate.queryForList("SELECT role FROM user_roles WHERE user_id = ? ORDER BY role", String.class, userId);
    }

    public void revoke(UUID sessionId, Instant revokedAt, UUID replacedBySessionId) {
        jdbcTemplate.update("""
                UPDATE refresh_sessions
                SET revoked_at = ?, replaced_by_session_id = ?
                WHERE id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), replacedBySessionId, sessionId);
    }

    public int revokeAll(UUID userId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE refresh_sessions SET revoked_at = ?
                WHERE user_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), userId);
    }

    public record RefreshSessionRecord(UUID id, UUID userId, String tokenHash, Instant expiresAt,
                                       Instant revokedAt, UUID replacedBySessionId, Instant createdAt) {
    }
}
