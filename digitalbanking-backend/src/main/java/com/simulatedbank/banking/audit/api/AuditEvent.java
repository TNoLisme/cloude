package com.simulatedbank.banking.audit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-owned audit event v1 (design/01 §4.3/§7.2). Consumers pass only allowlisted, already-masked state;
 * free-text notes, payloads, tokens, OTPs, passwords and full PII are never part of this contract.
 *
 * <p>{@code beforeState}/{@code afterState} are validated against the PII allowlist of the entity by the Audit
 * module; IDs and money values must be strings, counters may be integers (design/01 §7.2). {@code occurredAt} is
 * the application event time in UTC with microsecond precision; the writer normalises it.</p>
 *
 * @param action          catalog action; its {@link AuditClass} decides the transaction semantics
 * @param actorType       {@code USER} or {@code SYSTEM}
 * @param actorUserId     required when {@code actorType == USER}; must be {@code null} for {@code SYSTEM}
 * @param actorRole       role snapshot at event time ({@code ADMIN}, {@code OPERATIONS}, {@code CHECKER},
 *                        {@code SYSTEM}, ...), never a current-state join
 * @param entityType      catalog entity type (design/01 §6.3)
 * @param entityId        identifier of the affected entity, bounded decimal/opaque string
 * @param beforeState     masked snapshot before the change, {@code null} when there is none
 * @param afterState      masked snapshot after the change, {@code null} when there is none
 * @param amount          money amount for money events, otherwise {@code null}
 * @param currency        {@code VND} when amount is present
 * @param outcome         {@code SUCCESS}/{@code FAILURE}/{@code REJECTED}
 * @param correlationId   request correlation identifier (bounded)
 * @param requestId       server-generated request identifier (bounded, nullable)
 * @param clientInfoMasked masked IP/user-agent map, nullable
 * @param occurredAt      application event time
 */
public record AuditEvent(
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
        Instant occurredAt) {

    public AuditEvent {
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(actorType, "actorType is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        beforeState = copy(beforeState);
        afterState = copy(afterState);
        clientInfoMasked = copy(clientInfoMasked);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return Map.copyOf(source);
    }
}
