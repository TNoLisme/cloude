package com.simulatedbank.banking.admin.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One Admin idempotency row. The raw idempotency key is intentionally not retained in memory after the store
 * lookup; only the scope, canonical hash and replay response are needed by the application.
 *
 * <p>{@code responseBody} is the already-masked public response map captured by the first successful attempt;
 * it is never used as a business source of truth, only for exact replay (07 §5.4, design/02 §6.5).</p>
 */
public record AdminIdempotencyRecord(
        long recordId,
        AdminIdempotencyScope scope,
        String requestHash,
        AdminIdempotencyStatus status,
        Integer responseStatus,
        Map<String, Object> responseBody,
        Long approvalId,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt
) {

    public AdminIdempotencyRecord {
        if (recordId <= 0) {
            throw new IllegalArgumentException("recordId must be positive");
        }
        Objects.requireNonNull(scope, "scope is required");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lower-case SHA-256 hex");
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (status.terminal()) {
            if (completedAt == null || responseStatus == null || responseStatus < 100 || responseStatus > 599
                    || responseBody == null) {
                throw new IllegalArgumentException("Terminal idempotency record needs completed response fields");
            }
        } else if (completedAt != null || responseStatus != null || responseBody != null) {
            throw new IllegalArgumentException("In-progress idempotency record cannot carry a response");
        }
        if (scope.operation() == AdminIdempotencyOperation.DECIDE_APPROVAL && approvalId == null) {
            throw new IllegalArgumentException("Decision idempotency record needs approvalId");
        }
        responseBody = responseBody == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseBody));
    }

    public boolean terminal() {
        return status.terminal();
    }

    /** Replay payload; only valid for a terminal record. */
    public int requiredResponseStatus() {
        if (responseStatus == null) {
            throw new IllegalStateException("No stored response status");
        }
        return responseStatus;
    }

    /** Replay payload; only valid for a terminal record. */
    public Map<String, Object> requiredResponseBody() {
        if (responseBody == null) {
            throw new IllegalStateException("No stored response body");
        }
        return responseBody;
    }

    @Override
    public String toString() {
        return "AdminIdempotencyRecord[recordId=" + recordId + ", operation=" + scope.operation()
                + ", status=" + status + ", approvalId=" + approvalId + "]";
    }
}
