package com.simulatedbank.banking.transfer.adapter.out.persistence;

import com.simulatedbank.banking.transfer.application.TransferPendingMetrics.Snapshot;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention;
import com.simulatedbank.banking.transfer.application.TransferRecoveryPersistence;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Basis;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Mode;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Replay;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transfer-owned candidate SQL. The caller owns the primary transaction and acquires the customer guard first. */
public final class TransferRecoveryStore implements TransferRecoveryPersistence {
    private final JdbcTemplate jdbc;

    public TransferRecoveryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "primary JDBC");
    }

    @Override
    public Instant now() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).toInstant();
    }

    /** Read all PENDING rows including MANUAL; caller supplies the bounded read-only transaction. */
    public Snapshot pendingSnapshot() {
        requireTransaction();
        return jdbc.queryForObject("""
                SELECT count(*) AS pending_count,
                       GREATEST(0, EXTRACT(EPOCH FROM (statement_timestamp() - min(created_at)))) AS oldest_seconds
                FROM banking.transfer WHERE status = 'PENDING'
                """, (rs, row) -> new Snapshot(rs.getLong("pending_count"), rs.getDouble("oldest_seconds")));
    }

    @Override
    public List<Context> discover(Instant cutoff, Context after, int limit) {
        return jdbc.query("""
                SELECT transfer_id, customer_id, created_at, correlation_id FROM banking.transfer
                WHERE status = 'PENDING' AND (created_at <= ? OR attempt_state = 'FAILURE_PENDING')
                  AND (created_at, transfer_id) > (COALESCE(?, '-infinity'::timestamptz), ?)
                  AND ((attempt_state = 'IDLE' AND (recovery_next_attempt_at IS NULL
                            OR recovery_next_attempt_at <= statement_timestamp()
                            OR recovery_first_attempt_at + interval '15 minutes' <= statement_timestamp()))
                    OR (attempt_state = 'CLAIMED' AND attempt_lease_expires_at <= statement_timestamp())
                    OR attempt_state IN ('FAILURE_PENDING', 'DONE'))
                ORDER BY created_at, transfer_id LIMIT ?
                """, (rs, row) -> new Context(rs.getLong("transfer_id"), rs.getLong("customer_id"), time(rs, "created_at"),
                        rs.getString("correlation_id")),
                utc(cutoff), after == null ? null : utc(after.createdAt()),
                after == null ? 0L : after.transferId(), limit);
    }

    @Override
    public Optional<Locked> lock(Context context) {
        requireTransaction();
        return jdbc.query("""
                SELECT transfer_id, customer_id, created_at, source_account_id, destination_account_id,
                       amount, currency, correlation_id, status, attempt_state, attempt_epoch, attempt_token,
                       attempt_lease_expires_at, recovery_attempt_count, recovery_first_attempt_at, recovery_next_attempt_at
                FROM banking.transfer
                WHERE transfer_id = ? AND customer_id = ? AND created_at = ?
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Locked(new Intent(rs.getLong("transfer_id"), rs.getLong("customer_id"),
                rs.getLong("source_account_id"), rs.getLong("destination_account_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("correlation_id"), time(rs, "created_at")),
                rs.getString("status"), State.valueOf(rs.getString("attempt_state")), rs.getLong("attempt_epoch"),
                rs.getObject("attempt_token", UUID.class), time(rs, "attempt_lease_expires_at"),
                rs.getInt("recovery_attempt_count"), time(rs, "recovery_first_attempt_at"),
                time(rs, "recovery_next_attempt_at")), context.transferId(), context.customerId(),
                utc(context.createdAt())).stream().findFirst();
    }

    /** Shared check for the direct T2 adapter and recovery, under the guard and primary row lock. */
    public AttemptFence lockUsableFence(Context context) {
        return lockUsableFence(context, State.CLAIMED);
    }

    public AttemptFence lockUsableFence(Context context, State state) {
        Locked locked = lock(context).orElseThrow(() -> new IllegalStateException("Transfer fence lock unavailable"));
        if (!locked.matches(context) || !locked.pending() || locked.state() != state
                || !locked.leaseUntil().isAfter(now())) {
            throw new IllegalStateException("Transfer fence is unusable");
        }
        return new AttemptFence(locked.epoch(), locked.token());
    }

    @Override
    public AttemptFence claim(Locked locked, String owner, Instant leaseUntil, int count, Instant first, Instant next) {
        requireTransaction();
        AttemptFence fence = new AttemptFence(Math.incrementExact(locked.epoch()), UUID.randomUUID());
        requireOne(jdbc.update("""
                UPDATE banking.transfer SET attempt_epoch = ?, attempt_token = ?, attempt_owner = ?,
                    attempt_lease_expires_at = ?, attempt_state = 'CLAIMED', recovery_attempt_count = ?,
                    recovery_first_attempt_at = ?, recovery_next_attempt_at = ?
                WHERE transfer_id = ? AND status = 'PENDING' AND attempt_state = 'IDLE' AND attempt_epoch = ?
                """, fence.epoch(), fence.token(), owner, utc(leaseUntil), count, utc(first), utc(next),
                locked.intent().id(), locked.epoch()));
        return fence;
    }

    public Optional<Basis> findBasis(long id) {
        requireTransaction();
        return jdbc.query("""
                SELECT recovery_t1_policy, recovery_t1_date FROM banking.transfer WHERE transfer_id = ?
                  AND recovery_t1_policy IS NOT NULL
                """, (rs, row) -> new Basis(Mode.RESERVATION_REQUIRED,
                rs.getString(1), rs.getObject(2, LocalDate.class)), id).stream().findFirst();
    }

    public boolean hasNoResponse(long id) {
        requireTransaction();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM banking.idempotency_record
                    WHERE transfer_id = ? AND status IN ('RECEIVED', 'IN_PROGRESS')
                      AND response_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
                """, Boolean.class, id));
    }

    public Optional<String> originalReceipt(long id) {
        requireTransaction();
        return jdbc.query("""
                SELECT response_body::text FROM banking.idempotency_record
                WHERE transfer_id = ? AND response_status = 201 AND jsonb_typeof(response_body) = 'object'
                  AND status IN ('RECEIVED', 'IN_PROGRESS', 'COMPLETED')
                """, (rs, row) -> rs.getString(1), id).stream().findFirst();
    }

    @Override
    public void markFailurePending(Locked locked) {
        requireTransaction();
        requireOne(jdbc.update("""
                UPDATE banking.transfer SET attempt_state = 'FAILURE_PENDING'
                WHERE transfer_id = ? AND status = 'PENDING' AND attempt_state = 'CLAIMED'
                    AND attempt_epoch = ? AND attempt_token = ? AND attempt_lease_expires_at > clock_timestamp()
                """, locked.intent().id(), locked.epoch(), locked.token()));
    }

    /** The calling transaction has rechecked the lock; clearing the token fences delayed workers. */
    @Override
    public void clear(Locked locked, State next, Instant nextAt) {
        requireTransaction();
        requireOne(jdbc.update("""
                UPDATE banking.transfer SET attempt_state = ?, attempt_token = NULL, attempt_owner = NULL,
                    attempt_lease_expires_at = NULL, recovery_next_attempt_at = ?
                WHERE transfer_id = ? AND status = 'PENDING' AND attempt_state = ? AND attempt_epoch = ?
                    AND attempt_token IS NOT DISTINCT FROM CAST(? AS uuid)
                """, next.name(), utc(nextAt), locked.intent().id(), locked.state().name(), locked.epoch(), locked.token()));
    }

    @Override
    public void complete(Locked locked, Replay replay, Instant repairedAt, IdempotencyRetention.Snapshot ttl) {
        requireTransaction();
        long id = locked.intent().id();
        String response = replay.body().toString();
        // The idempotency row is owned by Transfer. Accept an existing terminal response only if replay is identical.
        Instant expiry = jdbc.queryForObject("""
                SELECT expires_at FROM banking.idempotency_record WHERE transfer_id = ?
                  AND ((status IN ('RECEIVED', 'IN_PROGRESS') AND completed_at IS NULL
                          AND (response_status IS NULL OR response_status = 201)
                          AND (response_body IS NULL OR response_body = CAST(? AS jsonb)))
                    OR (status = 'COMPLETED' AND completed_at = ?
                          AND response_status = 201 AND response_body = CAST(? AS jsonb)))
                FOR UPDATE
                """, (rs, row) -> time(rs, "expires_at"), id, response, utc(replay.terminalAt()), response);
        Instant terminalExpiry = IdempotencyRetention.terminalExpiry(expiry, repairedAt, ttl);
        requireOne(jdbc.update("""
                UPDATE banking.idempotency_record SET status = 'COMPLETED', response_status = 201,
                    response_body = CAST(? AS jsonb), completed_at = ?, expires_at = ?,
                    ttl_terminal_seconds = ?, ttl_terminal_config_version = ?
                WHERE transfer_id = ?
                """, response, utc(replay.terminalAt()), utc(terminalExpiry), ttl.seconds(), ttl.version(), id));
        requireOne(jdbc.update("""
                UPDATE banking.transfer SET status = 'COMPLETED', completed_at = ?, updated_at = ?,
                    attempt_state = 'DONE', attempt_token = NULL, attempt_owner = NULL,
                    attempt_lease_expires_at = NULL, recovery_next_attempt_at = NULL,
                    failure_code = NULL, failure_message = NULL
                WHERE transfer_id = ? AND status = 'PENDING' AND attempt_state = 'CLAIMED'
                    AND attempt_epoch = ? AND attempt_token = ? AND attempt_lease_expires_at > clock_timestamp()
                """, utc(replay.terminalAt()), utc(repairedAt), id, locked.epoch(), locked.token()));
    }

    @Override
    public int purge(int limit) {
        requireTransaction();
        return jdbc.update("""
                WITH expired AS (
                    SELECT i.transfer_id FROM banking.idempotency_record i
                    JOIN banking.transfer t ON t.transfer_id = i.transfer_id
                    WHERE i.status IN ('COMPLETED', 'FAILED') AND t.status = i.status
                      AND i.expires_at < statement_timestamp()
                    ORDER BY i.expires_at, i.transfer_id LIMIT ? FOR UPDATE OF i SKIP LOCKED
                )
                DELETE FROM banking.idempotency_record i USING expired e, banking.transfer t
                WHERE i.transfer_id = e.transfer_id AND t.transfer_id = e.transfer_id
                  AND i.status IN ('COMPLETED', 'FAILED') AND t.status = i.status
                  AND i.expires_at < statement_timestamp()
                """, limit);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Primary Transfer transaction required");
        }
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Transfer recovery state changed");
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
