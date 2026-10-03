package com.bank.simulator.transfer.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TransferJdbcRepository {
    private final JdbcTemplate jdbc;

    public TransferJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TransferRow find(UUID transferId) {
        List<TransferRow> rows = jdbc.query("""
                SELECT id, source_account_id, destination_account_id, amount::text AS amount, currency,
                       status, failure_code, memo, otp_challenge_id, created_at, expires_at, completed_at
                FROM transfers WHERE id = ?
                """, TransferJdbcRepository::map, transferId);
        return rows.stream().findFirst().orElse(null);
    }

    public UUID create(UUID sourceAccountId, UUID destinationAccountId, String amount, String currency,
                       String status, String memo, UUID otpChallengeId, UUID idempotencyId,
                       Instant createdAt, Instant expiresAt) {
        UUID transferId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers
                    (id, source_account_id, destination_account_id, amount, currency, status, memo,
                     otp_challenge_id, idempotency_record_id, created_at, expires_at, updated_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), ?, ?, ?, ?, ?, ?, ?, ?)
                """, transferId, sourceAccountId, destinationAccountId, amount, currency, status, memo,
                otpChallengeId, idempotencyId, Timestamp.from(createdAt),
                expiresAt == null ? null : Timestamp.from(expiresAt), Timestamp.from(createdAt));
        return transferId;
    }

    public List<TransferRow> findByAccount(UUID accountId) {
        return jdbc.query("""
                SELECT id, source_account_id, destination_account_id, amount::text AS amount, currency,
                       status, failure_code, memo, otp_challenge_id, created_at, expires_at, completed_at
                FROM transfers
                WHERE source_account_id = ? OR (destination_account_id = ? AND status = 'COMPLETED')
                ORDER BY created_at DESC, id DESC
                """, TransferJdbcRepository::map, accountId, accountId);
    }

    public void complete(UUID transferId, Instant completedAt) {
        jdbc.update("UPDATE transfers SET status = 'COMPLETED', completed_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(completedAt), Timestamp.from(completedAt), transferId);
    }

    public void fail(UUID transferId, String failureCode, Instant now) {
        jdbc.update("UPDATE transfers SET status = 'FAILED', failure_code = ?, updated_at = ? WHERE id = ?",
                failureCode, Timestamp.from(now), transferId);
    }

    public record TransferRow(UUID id, UUID sourceAccountId, UUID destinationAccountId, String amount,
                              String currency, String status, String failureCode, String memo,
                              UUID otpChallengeId, Instant createdAt, Instant expiresAt, Instant completedAt) {
    }

    private static TransferRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TransferRow(rs.getObject("id", UUID.class), rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class), rs.getString("amount"), rs.getString("currency"),
                rs.getString("status"), rs.getString("failure_code"), rs.getString("memo"),
                rs.getObject("otp_challenge_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }
}
