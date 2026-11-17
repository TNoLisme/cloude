package com.simulatedbank.banking.admin.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.port.ApprovalPayloadCanonicalizer;
import com.simulatedbank.banking.admin.application.port.ApprovalPayloadView;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ChangePayload;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic JSONB representation of typed Admin payloads.
 *
 * <p>Money/IDs are stored as decimal strings per 07 §2; timestamps use UTC ISO-8601. Field order is fixed so the
 * canonical request hash/audit comparison is stable. No REST DTO or provider entity is reused.</p>
 *
 * <p>{@link #canonicalJson(ChangePayload)} pipes the same map through the Common RFC 8785 canonicalizer so the
 * step-up request hash never depends on JSON field ordering or {@code toString()} output.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
class AdminPayloadJsonCodec implements ApprovalPayloadCanonicalizer, ApprovalPayloadView {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JcsCanonicalizer canonicalizer;

    AdminPayloadJsonCodec(JcsCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    @Override
    public byte[] canonicalJson(ChangePayload payload) {
        try {
            return canonicalizer.canonicalize(JSON.writeValueAsString(encode(payload)));
        } catch (JsonProcessingException exception) {
            // The caller only ever passes a validated Admin payload; never echo the value.
            throw new IllegalStateException("Approval payload is not canonicalizable");
        }
    }

    /**
     * Public payload projection. {@code expectedVersion} is server-captured provider state and is not part of the
     * public approval contract (CON-01 §4.2); it stays persisted but is omitted from responses and replay bodies.
     */
    @Override
    public Map<String, Object> publicPayload(ChangePayload payload) {
        Map<String, Object> values = new LinkedHashMap<>(encode(payload));
        if (payload instanceof RoleChangePayload) {
            values.remove("expectedVersion");
        }
        return Collections.unmodifiableMap(values);
    }

    Map<String, Object> encode(ChangePayload payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        switch (payload) {
            case LimitChangePayload limit -> {
                values.put("customerId", Long.toString(limit.customerId()));
                values.put("accountId", limit.accountId() == null ? null : Long.toString(limit.accountId()));
                values.put("perTransactionLimit", limit.perTransactionLimit().toPlainString());
                values.put("dailyLimit", limit.dailyLimit().toPlainString());
                values.put("currency", limit.currency());
                values.put("effectiveFrom", limit.effectiveFrom() == null ? null : limit.effectiveFrom().toString());
                values.put("reason", limit.reason());
            }
            case RoleChangePayload role -> {
                values.put("operation", role.operation().name());
                values.put("targetUserId", Long.toString(role.targetUserId()));
                values.put("roleCode", role.roleCode());
                values.put("privilegeCode", role.privilegeCode());
                values.put("expectedVersion", role.expectedVersion());
                values.put("reason", role.reason());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    ChangePayload decode(ApprovalRequestType requestType, Map<String, Object> values) {
        if (values == null) {
            throw new IllegalStateException("Stored approval payload is missing");
        }
        try {
            return switch (requestType) {
                case LIMIT_CHANGE -> new LimitChangePayload(
                        id(values, "customerId"),
                        values.get("accountId") == null ? null : id(values, "accountId"),
                        money(values, "perTransactionLimit"),
                        money(values, "dailyLimit"),
                        string(values, "currency"),
                        instant(values, "effectiveFrom"),
                        string(values, "reason"));
                case ROLE_CHANGE -> new RoleChangePayload(
                        RoleChangeOperation.valueOf(requiredString(values, "operation")),
                        id(values, "targetUserId"),
                        string(values, "roleCode"),
                        string(values, "privilegeCode"),
                        number(values, "expectedVersion"),
                        string(values, "reason"));
                case RETENTION_HOLD, RETENTION_POLICY_CHANGE -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored approval payload is invalid", exception);
        }
    }

    private static long id(Map<String, Object> values, String field) {
        String value = requiredString(values, field);
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    private static long number(Map<String, Object> values, String field) {
        Object value = required(values, field);
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof BigInteger) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException();
    }

    private static BigDecimal money(Map<String, Object> values, String field) {
        return new BigDecimal(requiredString(values, field));
    }

    private static Instant instant(Map<String, Object> values, String field) {
        String value = string(values, field);
        return value == null ? null : Instant.parse(value);
    }

    private static String string(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private static String requiredString(Map<String, Object> values, String field) {
        String value = string(values, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static Object required(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
