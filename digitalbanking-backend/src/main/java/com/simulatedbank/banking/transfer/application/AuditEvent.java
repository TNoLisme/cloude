package com.simulatedbank.banking.transfer.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Consumer-side C3 audit event proposal mirroring design/01 §4.3/§5.5 and TRFC-AU-02. The Audit
 * provider owns the canonical schema; values here must already be masked and allowlisted. No raw
 * key, body, full account number or free-text description may enter {@code afterState}.
 */
public record AuditEvent(String action, String actorType, Long actorUserId, String actorRole,
                         String entityType, String entityId, String outcome, String correlationId,
                         String requestId, Instant occurredAt, Map<String, String> afterState) {
    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        afterState = Map.copyOf(afterState);
    }

    @Override
    public String toString() {
        return "AuditEvent[action=" + action + ", outcome=" + outcome + ", correlationId=" + correlationId + "]";
    }
}