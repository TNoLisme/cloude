package com.simulatedbank.banking.common.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.context.RequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.event.KeyValuePair;

/** Fail-closed stdout boundary: never serialize arbitrary messages, arguments, MDC or throwables. */
public final class SafeJsonLogEncoder extends EncoderBase<ILoggingEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private String service = "digital-banking-backend";
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
        // Only the fixed completion event can contribute HTTP fields. Other log content is omitted.
        boolean completion = event.getLoggerName().equals("com.simulatedbank.banking.common.context.HttpRequestLog")
                && event.getMessage().equals("HTTP_REQUEST_COMPLETED");
        fields.put("event", completion ? "HTTP_REQUEST_COMPLETED" : "APPLICATION_LOG");
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

    private static boolean isErrorCode(String value) {
        try {
            com.simulatedbank.banking.common.error.ErrorCode.valueOf(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
