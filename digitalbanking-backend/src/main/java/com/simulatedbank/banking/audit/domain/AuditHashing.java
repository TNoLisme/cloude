package com.simulatedbank.banking.audit.domain;

import com.simulatedbank.banking.common.crypto.HmacService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditHashing {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private AuditHashing() { }

    public static String recordHash(String canonicalJson, String previousHash) {
        if (canonicalJson == null || previousHash == null || !previousHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Canonical payload and previous hash are required");
        }
        return HmacService.sha256Hex(canonicalJson + "|" + previousHash);
    }

    public static String canonicalPayload(AuditEvent event, Instant occurredAt) {
        if (occurredAt == null) throw new IllegalArgumentException("Event time is required");
        AuditEventValidator.validate(event);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("occurred_at", UTC_MICROS.format(occurredAt));
        payload.put("actor_type", event.actorType());
        payload.put("actor_user_id", event.actorUserId() == null ? null : Long.toString(event.actorUserId()));
        payload.put("actor_role", event.actorRole());
        payload.put("action", event.action());
        payload.put("entity_type", event.entityType());
        payload.put("entity_id", event.entityId());
        payload.put("before_state", event.beforeState());
        payload.put("after_state", event.afterState());
        payload.put("amount", event.amount() == null ? null : event.amount().setScale(2).toPlainString());
        payload.put("currency", event.currency());
        payload.put("correlation_id", event.correlationId());
        payload.put("request_id", event.requestId());
        payload.put("outcome", event.outcome());
        payload.put("client_info_masked", event.clientInfoMasked());
        payload.put("hash_version", 1);
        var canonical = AuditCanonicalJson.canonicalize(payload);
        if (canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
            throw new IllegalArgumentException("Audit payload exceeds 32 KiB");
        }
        return canonical;
    }
}
