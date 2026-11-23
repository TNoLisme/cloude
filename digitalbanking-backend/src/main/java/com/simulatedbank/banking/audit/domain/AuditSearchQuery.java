package com.simulatedbank.banking.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Validated internal search filters for audit records. */
public record AuditSearchQuery(Instant from, Instant to, int page, int size, boolean ascending,
                               String correlationId, String reference, Long actorUserId, String actorType,
                               String action, String entityType, String entityId, String outcome) {
    public AuditSearchQuery {
        Objects.requireNonNull(from, "from is required");
        Objects.requireNonNull(to, "to is required");
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("Invalid audit time window");
        }
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid audit pagination");
        if (actorUserId != null && actorUserId <= 0) throw new IllegalArgumentException("Invalid actor user ID");
        if (actorType != null && !java.util.Set.of("USER", "SYSTEM").contains(actorType)) {
            throw new IllegalArgumentException("Invalid actor type filter");
        }
        if (outcome != null && !java.util.Set.of("SUCCESS", "FAILURE", "REJECTED").contains(outcome)) {
            throw new IllegalArgumentException("Invalid outcome filter");
        }
        validateSafe(correlationId);
        validateSafe(reference);
        validateSafe(actorType);
        validateSafe(action);
        validateSafe(entityType);
        validateSafe(entityId);
        validateSafe(outcome);
    }

    public static AuditSearchQuery create(Instant from, Instant to, Integer page, Integer size, String sort, Instant now) {
        return create(from, to, page, size, sort, now, null, null, null, null, null, null, null, null);
    }

    public static AuditSearchQuery create(Instant from, Instant to, Integer page, Integer size, String sort, Instant now,
                                          String correlationId, String reference, Long actorUserId, String actorType,
                                          String action, String entityType, String entityId, String outcome) {
        Objects.requireNonNull(now, "now is required");
        boolean ascending;
        if (sort == null || sort.equals("occurredAt,desc")) ascending = false;
        else if (sort.equals("occurredAt,asc")) ascending = true;
        else throw new IllegalArgumentException("Invalid audit sort");
        return new AuditSearchQuery(from == null ? now.minus(Duration.ofDays(30)) : from,
                to == null ? now : to, page == null ? 0 : page, size == null ? 20 : size, ascending,
                correlationId, reference, actorUserId, actorType, action, entityType, entityId, outcome);
    }

    public long offset() { return (long) page * size; }

    private static void validateSafe(String value) {
        if (value != null && !value.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid audit filter");
        }
    }
}
