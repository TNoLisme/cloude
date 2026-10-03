package com.bank.simulator.transfer.infrastructure.persistence;

import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TransferOtpRepository {
    private final JdbcTemplate jdbc;

    public TransferOtpRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID transferId, UUID challengeId, String phone, String otpHash,
                       Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO otp_challenges (id, identifier_normalized, channel, purpose, otp_hash,
                    created_at, expires_at, max_attempts)
                VALUES (?, ?, 'SMS', 'TRANSFER_STEP_UP', ?, ?, ?, 5)
                """, challengeId, phone, otpHash, Timestamp.from(createdAt), Timestamp.from(expiresAt));
        jdbc.update("UPDATE transfers SET otp_challenge_id = ? WHERE id = ?", challengeId, transferId);
    }

    public OtpChallengeRecord lock(UUID challengeId, Instant now) {
        List<OtpChallengeRecord> rows = jdbc.query("""
                SELECT id, identifier_normalized, channel, purpose, otp_hash, attempts, max_attempts,
                       expires_at, consumed_at, invalidated_at
                FROM otp_challenges WHERE id = ? FOR UPDATE
                """, (rs, row) -> new OtpChallengeRecord(rs.getObject("id", UUID.class),
                rs.getString("identifier_normalized"), rs.getString("channel"), rs.getString("purpose"),
                rs.getString("otp_hash"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()), challengeId);
        return rows.stream().findFirst().orElse(null);
    }

    public void incrementAttempts(UUID id, int attempts, Instant invalidateAt) {
        jdbc.update("UPDATE otp_challenges SET attempts = ?, invalidated_at = ? WHERE id = ?",
                attempts, invalidateAt == null ? null : Timestamp.from(invalidateAt), id);
    }

    public void consume(UUID id, Instant consumedAt) {
        jdbc.update("UPDATE otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL",
                Timestamp.from(consumedAt), id);
    }

    public void invalidate(UUID id, Instant at) {
        jdbc.update("UPDATE otp_challenges SET invalidated_at = ? WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL",
                Timestamp.from(at), id);
    }
}
