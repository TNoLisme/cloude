package com.simulatedbank.banking.transfer.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Classifies a successful primary-database lookup. A failed lookup must propagate
 * as an error, not be represented by an empty Optional. This decision does not
 * acquire locks, reserve a key, or authorize access to a stored response.
 */
public enum IdempotencyDecision {
    NEW, REPLAY, IN_PROGRESS, CONFLICT;

    public static IdempotencyDecision classify(long actorId, long customerId, String requestHash,
                                                Optional<Existing> existing) {
        requireIdentity(actorId, customerId, requestHash);
        Objects.requireNonNull(existing, "existing lookup result");
        if (existing.isEmpty()) {
            return NEW;
        }
        Existing stored = existing.get();
        // Compare ownership before revealing whether an operation has finished.
        if (stored.actorId() != actorId || stored.customerId() != customerId
                || !stored.requestHash().equals(requestHash)) {
            return CONFLICT;
        }
        return switch (stored.status()) {
            case RECEIVED, IN_PROGRESS -> IN_PROGRESS;
            case COMPLETED, FAILED -> REPLAY;
        };
    }

    private static void requireIdentity(long actorId, long customerId, String hash) {
        if (actorId <= 0 || customerId <= 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid idempotency identity");
        }
    }

    /** Contains no key or stored response; its string form does not expose the hash. */
    public record Existing(long actorId, long customerId, String requestHash, IdempotencyStatus status) {
        public Existing {
            requireIdentity(actorId, customerId, requestHash);
            Objects.requireNonNull(status, "status");
        }

        @Override
        public String toString() {
            return "Existing[redacted]";
        }
    }
}
