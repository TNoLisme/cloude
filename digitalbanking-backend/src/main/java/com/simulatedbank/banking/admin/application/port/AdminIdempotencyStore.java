package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-owned persistence boundary for {@code banking.admin_idempotency_record}.
 *
 * <p>{@link #claim} is insert-first: the database unique index decides the winner and
 * {@code ON CONFLICT DO NOTHING} keeps the caller transaction usable. A plain insert + catch
 * {@code DataIntegrityViolationException} would abort the transaction (design/02 §6.4).</p>
 *
 * <p>{@link #claim} and {@link #complete} must join the caller transaction ({@code MANDATORY}) so the business
 * row, the final idempotency state and the response commit atomically. {@link #find} is read-only.</p>
 */
public interface AdminIdempotencyStore {

    Optional<AdminIdempotencyRecord> find(AdminIdempotencyScope scope, String idempotencyKey);

    /**
     * Attempts the unique insert. Returns the inserted RECEIVED record, or empty when another committed record
     * already owns the scope/key (including a record inserted by a concurrent transaction after it committed).
     */
    Optional<AdminIdempotencyRecord> claim(AdminIdempotencyScope scope, String idempotencyKey, String requestHash,
            Instant createdAt, Instant expiresAt);

    /** Marks a claimed record COMPLETED and stores the exact replay response. Fails if not RECEIVED. */
    void complete(long recordId, int responseStatus, Map<String, Object> responseBody, long approvalId,
            Instant completedAt, Instant expiresAt);

    /**
     * Deletes terminal records whose {@code expires_at} is before {@code now}; never deletes RECEIVED.
     * Uses {@code FOR UPDATE SKIP LOCKED} so concurrent purge runs cannot delete the same row twice.
     */
    int purgeExpired(Instant now, int batchSize);
}
