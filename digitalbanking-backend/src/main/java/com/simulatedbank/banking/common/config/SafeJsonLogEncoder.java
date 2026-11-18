package com.simulatedbank.banking.common.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.context.RequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.event.KeyValuePair;
/**
 * Fail-closed stdout boundary: chỉ HTTP completion và security-audit field allowlist được
 * serialize; không bao giờ ghi raw message, arguments, MDC hoặc throwable.
 */
public final class SafeJsonLogEncoder extends EncoderBase<ILoggingEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private String service = "digital-banking-backend";
    private static final Set<String> AUDIT_LOGGERS = Set.of(
            "com.simulatedbank.banking.common.AUDIT",
            "com.simulatedbank.banking.customer.AUDIT",
            "com.simulatedbank.banking.admin.AUDIT");
    private static final Set<String> AUDIT_EVENTS = Set.of(
            "SECURITY_AUDIT", "SECURITY_AUDIT_WRITE_FAILED");
    private static final Set<String> AUDIT_ENTITY_TYPES = Set.of(
            "CUSTOMER", "ACCOUNT", "TRANSFER", "LIMIT_CONFIG", "AUDIT_LOG",
            "APPROVAL_REQUEST", "FRAUD_FLAG", "AUTH_CHALLENGE", "SESSION", "UNKNOWN");
    /** Admin approval catalog actions (design/01 §5.3) reachable through the C2/C3 adapter. */
    private static final Set<String> ADMIN_AUDIT_ACTIONS = Set.of(
            "LIMIT_CHANGE_REQUESTED", "LIMIT_CHANGE_APPROVED", "LIMIT_CHANGE_REJECTED",
            "ROLE_CHANGE_REQUESTED", "ROLE_CHANGE_APPROVED", "ROLE_CHANGE_REJECTED");
    private static final Set<String> APPROVAL_REQUEST_TYPES = Set.of("LIMIT_CHANGE", "ROLE_CHANGE");
    private String environment = "local";

    public void setService(String service) { this.service = service; }
    public void setEnvironment(String environment) { this.environment = environment; }
    @Override public byte[] headerBytes() { return null; }
    @Override public byte[] footerBytes() { return null; }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("service", service);
        fields.put("environment", environment);
        fields.put("logger", event.getLoggerName());
        String correlation = event.getMDCPropertyMap().get("correlationId");
        String request = event.getMDCPropertyMap().get("requestId");
        fields.put("correlation_id", RequestContext.isValidCorrelationId(correlation) ? correlation : null);
        fields.put("request_id", request != null && request.matches("[0-9a-f-]{36}") ? request : null);
        boolean completion = event.getLoggerName().equals("com.simulatedbank.banking.common.context.HttpRequestLog")
                && event.getMessage().equals("HTTP_REQUEST_COMPLETED");
        boolean securityAudit = AUDIT_LOGGERS.contains(event.getLoggerName())
                && AUDIT_EVENTS.contains(event.getMessage());
        fields.put("event", completion ? "HTTP_REQUEST_COMPLETED"
                : securityAudit ? event.getMessage() : "APPLICATION_LOG");
        if (completion && event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                Object value = pair.value;
                boolean allowed = switch (pair.key) {
                    case "route" -> value instanceof String s && s.length() <= 256
                            && s.matches("[A-Za-z0-9/{}_.:*?-]+");
                    case "method" -> value instanceof String s && s.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|OTHER");
                    case "status" -> value instanceof Integer n && n >= 100 && n <= 599;
                    case "latency_ms" -> value instanceof Long n && n >= 0;
                    case "user_id" -> value instanceof String s && s.matches("sha256:[0-9a-f]{64}");
                    case "action" -> "HTTP_REQUEST".equals(value);
                    case "outcome" -> "SUCCESS".equals(value) || "CLIENT_ERROR".equals(value) || "SERVER_ERROR".equals(value);
                    case "error_code" -> value instanceof String s && isErrorCode(s);
                    default -> false;
                };
                if (allowed) fields.put(pair.key, value);
            }
        } else if (securityAudit) {
            addSecurityAuditFields(fields, event);
        }
        if (event.getThrowableProxy() != null) {
            fields.put("exception_type", event.getThrowableProxy().getClassName());
        }
        try {
            return (mapper.writeValueAsString(fields) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            // No fallback to raw data, even if encoding fails.
            return "{\"event\":\"LOG_ENCODING_FAILED\"}\n".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void addSecurityAuditFields(Map<String, Object> fields, ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return;
        }
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            Object value = pair.value;
            boolean allowed = switch (pair.key) {
                case "action" -> isAuditAction(value);
                case "audit_class" -> "C2".equals(value) || "C3".equals(value);
                case "outcome" -> "REJECTED".equals(value) || "WRITE_FAILED".equals(value)
                        || "SUCCESS".equals(value);
                case "actor_user_id" -> value == null || value instanceof Long id && id > 0;
                case "actor_roles" -> value instanceof String roles && roles.length() <= 128
                        && roles.matches("(?:[A-Z][A-Z0-9_]*(?:,[A-Z][A-Z0-9_]*)*)?");
                case "entity_type" -> value instanceof String type && AUDIT_ENTITY_TYPES.contains(type);
                case "entity_id" -> value == null
                        || value instanceof String id && id.matches("[1-9][0-9]{0,18}");
                case "request_type" -> value == null
                        || value instanceof String type && APPROVAL_REQUEST_TYPES.contains(type);
                case "reason_code" -> value == null || value instanceof String code && isErrorCode(code);
                case "correlation_id" -> value instanceof String id && RequestContext.isValidCorrelationId(id);
                case "request_id" -> value instanceof String id && id.matches("[0-9a-f-]{36}");
                default -> false;
            };
            if (allowed) {
                fields.put(pair.key, value);
            }
        }
    }

    private static boolean isAuditAction(Object value) {
        return "SECURITY_ACCESS_DENIED".equals(value)
                || value instanceof String action && ADMIN_AUDIT_ACTIONS.contains(action);
    }

    private static boolean isErrorCode(String value) {
        try {
            com.simulatedbank.banking.common.error.ErrorCode.valueOf(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
