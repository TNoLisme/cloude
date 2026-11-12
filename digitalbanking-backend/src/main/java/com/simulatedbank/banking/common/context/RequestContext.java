package com.simulatedbank.banking.common.context;

import java.util.UUID;

/** Immutable snapshot. Metadata is for authorized consumers, never a logging payload. */
public record RequestContext(String correlationId, String requestId, String clientIp, String userAgent) {

    public static boolean isValidCorrelationId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,64}");
    }

    public RequestContext {
        if (!isValidCorrelationId(correlationId)) {
            throw new IllegalArgumentException("Invalid correlation ID");
        }
        if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) {
            throw new IllegalArgumentException("Invalid server request ID");
        }
        // Never retain raw IPs; IPv6/unknown formats fail closed until a masking policy exists.
        clientIp = clientIp != null && clientIp.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                ? clientIp.substring(0, clientIp.lastIndexOf('.') + 1) + "***" : null;
        if (userAgent != null) {
            userAgent = userAgent.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "");
            userAgent = userAgent.substring(0, Math.min(256, userAgent.length()));
        }
    }

    @Override
    public String toString() {
        return "RequestContext[correlationId=" + correlationId + ", requestId=" + requestId + "]";
    }
}
