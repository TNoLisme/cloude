package com.simulatedbank.banking.admin.domain;

import java.util.Objects;

/**
 * Key scope of one Admin idempotency record: authenticated actor + operation + resource.
 *
 * <p>Schema v1 is single-tenant (04 §3/§4.5.3 has no tenant column), so actor isolation is the tenant boundary.
 * When multi-tenancy is added, the tenant must be added to this scope and to the database unique index before an
 * Admin write endpoint is exposed across tenants.</p>
 *
 * <p>{@code scopeKey} is the empty string for proposal creation (no resource exists yet) and the persisted
 * {@code approvalId} for decisions. The raw idempotency key is deliberately not part of this value object so it
 * cannot leak through logs or {@code toString()}.</p>
 */
public record AdminIdempotencyScope(long actorId, AdminIdempotencyOperation operation, String scopeKey) {

    private static final int MAX_SCOPE_KEY = 64;

    public AdminIdempotencyScope {
        if (actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
        Objects.requireNonNull(operation, "operation is required");
        scopeKey = scopeKey == null ? "" : scopeKey;
        if (scopeKey.length() > MAX_SCOPE_KEY) {
            throw new IllegalArgumentException("scopeKey is too long");
        }
    }

    public static AdminIdempotencyScope proposal(long actorId, AdminIdempotencyOperation operation) {
        return new AdminIdempotencyScope(actorId, operation, "");
    }

    public static AdminIdempotencyScope decision(long actorId, long approvalId) {
        if (approvalId <= 0) {
            throw new IllegalArgumentException("approvalId must be positive");
        }
        return new AdminIdempotencyScope(actorId, AdminIdempotencyOperation.DECIDE_APPROVAL,
                Long.toString(approvalId));
    }

    @Override
    public String toString() {
        return "AdminIdempotencyScope[actorId=" + actorId + ", operation=" + operation
                + ", scopeKey=" + scopeKey + "]";
    }
}
