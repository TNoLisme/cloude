package com.simulatedbank.banking.audit.domain;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Event that passed catalog, actor, allowlist, PII and size validation, together with its canonical JSON payload
 * (design/01 §5.6/§7.2). Only the validator creates instances; the writer trusts every invariant here.
 *
 * <p>{@code occurredAt} is already truncated to microseconds, {@code amount} is scaled to two decimals and all
 * state maps are immutable copies containing only scalars.</p>
 */
public record ValidatedAuditEvent(
        AuditAction action,
        AuditActorType actorType,
        Long actorUserId,
        String actorRole,
        String entityType,
        String entityId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        BigDecimal amount,
        String currency,
        AuditOutcome outcome,
        String correlationId,
        String requestId,
        Map<String, Object> clientInfoMasked,
        Instant occurredAt,
        int hashVersion,
        String canonicalJson) {

    public ValidatedAuditEvent {
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(actorType, "actorType is required");
        Objects.requireNonNull(actorRole, "actorRole is required");
        Objects.requireNonNull(entityType, "entityType is required");
        Objects.requireNonNull(entityId, "entityId is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        if (hashVersion < 1) {
            throw new IllegalArgumentException("hashVersion must be positive");
        }
        if (actorType == AuditActorType.USER && actorUserId == null) {
            throw new IllegalArgumentException("USER events require actorUserId");
        }
        if (actorType == AuditActorType.SYSTEM && actorUserId != null) {
            throw new IllegalArgumentException("SYSTEM events must not carry actorUserId");
        }
        beforeState = copy(beforeState);
        afterState = copy(afterState);
        clientInfoMasked = copy(clientInfoMasked);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? null : Map.copyOf(source);
    }
}
