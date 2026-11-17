package com.simulatedbank.banking.transfer.adapter.out.persistence;

import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfer-owned SQL primitives. Callers supply the transaction and acquire the
 * customer guard and transfer/fence locks required by the module contract.
 * These operations alone are not a money-transfer orchestration.
 * This candidate is not component-scanned; register it explicitly only after the
 * reviewed Transfer migration and its provider prerequisites have been applied.
 */
@Transactional(propagation = Propagation.MANDATORY)
public class TransferPersistence {
    private final JdbcTemplate jdbc;

    public TransferPersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert both T1 rows. A key conflict throws through the transaction proxy so
     * the entire caller transaction is marked rollback-only, even if caught.
     * A caller may read the existing key only after rollback in a new transaction.
     */
    public PendingTransfer insertPending(PendingInsert input) {
        Objects.requireNonNull(input, "input");
        PendingTransfer transfer = jdbc.queryForObject("""
                INSERT INTO banking.transfer
                    (reference_no, customer_id, initiated_by_user_id, source_account_id,
                     destination_account_id, amount, currency, description, correlation_id,
                     step_up_challenge_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING transfer_id, created_at
                """, (rs, row) -> new PendingTransfer(
                rs.getLong("transfer_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                input.identity().reference(), input.identity().customerId(), input.identity().initiatedByUserId(),
                input.identity().sourceAccountId(), input.identity().destinationAccountId(), input.amount().value(),
                input.amount().currency(), input.description(), input.correlationId(), input.identity().stepUpChallengeId());
        Objects.requireNonNull(transfer, "insert returned no transfer");

        Instant expiry = transfer.createdAt().plusSeconds(input.ttlSeconds());
        List<Long> inserted = jdbc.query("""
                INSERT INTO banking.idempotency_record
                    (idempotency_key, request_hash, transfer_id, created_at, expires_at,
                     ttl_t1_seconds, ttl_t1_config_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING transfer_id
                """, (rs, row) -> rs.getLong(1), input.idempotencyKey(), input.requestHash(), transfer.id(),
                utc(transfer.createdAt()), utc(expiry), input.ttlSeconds(), input.ttlConfigVersion());
        if (inserted.isEmpty()) {
            throw new DuplicateIdempotencyKeyException();
        }
        return transfer;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record PendingTransfer(long id, Instant createdAt) { }

    /** Sensitive key/hash are intentionally omitted from the generated string representation. */
    public record PendingInsert(TransferIdentity identity, TransferAmount amount, String description,
                                String correlationId, String idempotencyKey, String requestHash,
                                long ttlSeconds, String ttlConfigVersion) {
        public PendingInsert {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(amount, "amount");
            requireLength(correlationId, 1, 64, "correlation ID");
            requireLength(idempotencyKey, 1, 255, "idempotency key");
            if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("A SHA-256 request hash is required");
            }
            if (description != null && description.codePointCount(0, description.length()) > 255) {
                throw new IllegalArgumentException("Description is too long");
            }
            if (ttlSeconds < 86400) {
                throw new IllegalArgumentException("TTL must be at least 24 hours");
            }
            requireLength(ttlConfigVersion, 1, 128, "TTL config version");
            if (ttlConfigVersion.isBlank()) {
                throw new IllegalArgumentException("Invalid TTL config version");
            }
        }

        @Override
        public String toString() {
            return "PendingInsert[redacted]";
        }
    }

    private static void requireLength(String value, int min, int max, String name) {
        if (value == null || value.codePointCount(0, value.length()) < min
                || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    public static class DuplicateIdempotencyKeyException extends RuntimeException {
        public DuplicateIdempotencyKeyException() {
            super("Idempotency key already exists; transaction must roll back");
        }
    }
}
