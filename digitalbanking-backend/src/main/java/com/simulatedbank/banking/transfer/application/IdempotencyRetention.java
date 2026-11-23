package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import java.time.Instant;
import java.util.Objects;

/** Retention calculations only; callers must lock and recheck before deleting. */
public final class IdempotencyRetention {
    private static final long MIN_TTL_SECONDS = 86_400;

    private IdempotencyRetention() { }

    /** Take a new configuration snapshot for T1 and another for terminalization. */
    public record Snapshot(long seconds, String version) {
        public Snapshot {
            if (seconds < MIN_TTL_SECONDS || version == null || version.isBlank()
                    || version.codePointCount(0, version.length()) > 128) {
                throw new IllegalArgumentException("Invalid idempotency retention configuration");
            }
        }
    }

    public static Instant initialExpiry(Instant createdAt, Snapshot snapshot) {
        return Objects.requireNonNull(createdAt, "createdAt")
                .plusSeconds(Objects.requireNonNull(snapshot, "snapshot").seconds());
    }

    public static Instant terminalExpiry(Instant existingExpiry, Instant terminalAt, Snapshot snapshot) {
        Objects.requireNonNull(existingExpiry, "existingExpiry");
        Instant candidate = Objects.requireNonNull(terminalAt, "terminalAt")
                .plusSeconds(Objects.requireNonNull(snapshot, "snapshot").seconds());
        return existingExpiry.isAfter(candidate) ? existingExpiry : candidate;
    }

    /** Eligibility is not authorization to delete: serialize with replay and recheck in the database. */
    public static boolean eligibleForPurge(IdempotencyStatus status, Instant expiresAt, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        return (status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED)
                && expiresAt.isBefore(now);
    }
}
