package com.bank.simulator.identity.infrastructure.otp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOtpChallengeRepository implements OtpChallengeRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOtpChallengeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(UUID id, String identifier, String channel, String purpose, String otpHash,
                       Instant createdAt, Instant expiresAt, int maxAttempts) {
        jdbcTemplate.update("""
                INSERT INTO otp_challenges (id, identifier_normalized, channel, purpose, otp_hash,
                    attempts, max_attempts, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, id, normalize(identifier), channel, purpose, otpHash, maxAttempts,
                Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    @Override
    public OtpChallengeRecord lockChallenge(UUID id, String identifier, String channel, String purpose, Instant now) {
        List<OtpChallengeRecord> records = jdbcTemplate.query("""
                SELECT id, identifier_normalized, channel, purpose, otp_hash, attempts, max_attempts,
                       expires_at, consumed_at, invalidated_at
                FROM otp_challenges
                WHERE id = ? AND identifier_normalized = ? AND channel = ? AND purpose = ?
                FOR UPDATE
                """, (rs, row) -> new OtpChallengeRecord(
                rs.getObject("id", UUID.class), rs.getString("identifier_normalized"), rs.getString("channel"),
                rs.getString("purpose"), rs.getString("otp_hash"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()),
                id, normalize(identifier), channel, purpose);
        return records.stream().findFirst().orElse(null);
    }

    @Override
    public OtpChallengeRecord lockLatestActive(String identifier, String channel, String purpose, Instant now) {
        List<OtpChallengeRecord> records = jdbcTemplate.query("""
                SELECT id, identifier_normalized, channel, purpose, otp_hash, attempts, max_attempts,
                       expires_at, consumed_at, invalidated_at
                FROM otp_challenges
                WHERE identifier_normalized = ? AND channel = ? AND purpose = ?
                  AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                FOR UPDATE
                """, (rs, row) -> new OtpChallengeRecord(
                rs.getObject("id", UUID.class), rs.getString("identifier_normalized"), rs.getString("channel"),
                rs.getString("purpose"), rs.getString("otp_hash"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()),
                normalize(identifier), channel, purpose, Timestamp.from(now));
        return records.stream().findFirst().orElse(null);
    }

    @Override
    public void incrementAttempts(UUID id, int attempts, Instant invalidatedAt) {
        jdbcTemplate.update("UPDATE otp_challenges SET attempts = ?, invalidated_at = ? WHERE id = ?",
                attempts, invalidatedAt == null ? null : Timestamp.from(invalidatedAt), id);
    }

    @Override
    public void invalidate(UUID id, Instant invalidatedAt) {
        jdbcTemplate.update("UPDATE otp_challenges SET invalidated_at = ? WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL",
                Timestamp.from(invalidatedAt), id);
    }

    @Override
    public void invalidateActive(String identifier, String channel, String purpose, Instant invalidatedAt) {
        jdbcTemplate.update("""
                UPDATE otp_challenges SET invalidated_at = ?
                WHERE identifier_normalized = ? AND channel = ? AND purpose = ?
                  AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(invalidatedAt), normalize(identifier), channel, purpose);
    }

    @Override
    public boolean consume(UUID id, Instant consumedAt) {
        return jdbcTemplate.update("UPDATE otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL",
                Timestamp.from(consumedAt), id) == 1;
    }

    private String normalize(String identifier) {
        return identifier.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
