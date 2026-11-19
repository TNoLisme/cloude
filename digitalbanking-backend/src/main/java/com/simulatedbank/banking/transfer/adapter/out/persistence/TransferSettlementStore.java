package com.simulatedbank.banking.transfer.adapter.out.persistence;

import com.simulatedbank.banking.transfer.application.IdempotencyRetention.Snapshot;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.ReplayResponse;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transfer-owned SQL for reading an intent and finalizing the two terminal rows. */
public class TransferSettlementStore {
    private final JdbcTemplate jdbc;

    public TransferSettlementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Details read(long id) {
        return jdbc.queryForObject("""
                SELECT customer_id, source_account_id, destination_account_id, amount, currency, correlation_id,
                       created_at, reference_no, initiated_by_user_id, description
                FROM banking.transfer WHERE transfer_id = ?
                """, (rs, row) -> new Details(new Intent(id, rs.getLong("customer_id"),
                rs.getLong("source_account_id"), rs.getLong("destination_account_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                rs.getString("reference_no"), rs.getLong("initiated_by_user_id"), rs.getString("description")), id);
    }

    public Instant primaryTime() {
        requireWritableTransaction();
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    public void setTimeouts(long lockMs, long statementMs) {
        requireWritableTransaction();
        jdbc.query("SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)",
                rs -> null, lockMs + "ms", statementMs + "ms");
    }

    public ReplayResponse terminal(long id, AttemptFence fence, Instant instant, String failureCode,
            ReplayResponse response, Snapshot ttl) {
        requireWritableTransaction();
        String status = failureCode == null ? "COMPLETED" : "FAILED";
        OffsetDateTime at = instant.atOffset(ZoneOffset.UTC);
        int matched = jdbc.update("""
                UPDATE banking.transfer
                SET status = ?, completed_at = ?, failed_at = ?, updated_at = ?, failure_code = ?,
                    failure_message = NULL, attempt_state = 'DONE', attempt_token = NULL, attempt_owner = NULL,
                    attempt_lease_expires_at = NULL, recovery_next_attempt_at = NULL
                WHERE transfer_id = ? AND status = 'PENDING' AND attempt_state = 'CLAIMED'
                      AND attempt_epoch = ? AND attempt_token = ?
                """, status, failureCode == null ? at : null, failureCode == null ? null : at, at, failureCode,
                id, fence.epoch(), fence.token());
        if (matched != 1) throw new IllegalStateException("Transfer terminal fence changed");
        return jdbc.queryForObject("""
                UPDATE banking.idempotency_record
                SET status = ?, response_status = ?, response_body = CAST(? AS jsonb), completed_at = ?,
                    expires_at = GREATEST(expires_at, ?), ttl_terminal_seconds = ?, ttl_terminal_config_version = ?
                WHERE transfer_id = ? AND status IN ('RECEIVED', 'IN_PROGRESS')
                      AND completed_at IS NULL AND response_status IS NULL AND response_body IS NULL
                RETURNING response_body::text
                """, (rs, row) -> new ReplayResponse(response.httpStatus(), rs.getString(1)),
                status, response.httpStatus(), response.safeBody(), at,
                instant.plusSeconds(ttl.seconds()).atOffset(ZoneOffset.UTC), ttl.seconds(), ttl.version(), id);
    }

    /** Compare against this executor's prepared reply after an ambiguous commit. No writes. */
    public boolean hasCommittedResponse(long id, AttemptFence fence, Instant at, ReplayResponse response) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM banking.transfer t JOIN banking.idempotency_record i USING (transfer_id)
                    WHERE t.transfer_id = ? AND t.attempt_epoch = ? AND t.attempt_state = 'DONE'
                      AND t.status IN ('COMPLETED', 'FAILED') AND t.status = i.status
                      AND COALESCE(t.completed_at, t.failed_at) = i.completed_at AND i.completed_at = ?
                      AND i.response_status = ? AND i.response_body = CAST(? AS jsonb))
                """, Boolean.class, id, fence.epoch(), at.atOffset(ZoneOffset.UTC),
                response.httpStatus(), response.safeBody()));
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Writable primary Transfer transaction required");
        }
    }

    public record Details(Intent intent, String reference, long userId, String description) {
        public Context context() {
            return new Context(intent.id(), intent.customerId(), intent.createdAt(), intent.correlationId());
        }

        @Override public String toString() { return "SettlementDetails[redacted]"; }
    }
}
