package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.context.HttpRequestLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fixed, sanitized security audit fallback event understood by the shared JSON encoder. */
public final class SecurityAuditGapLogger {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditGapLogger.class);

    private SecurityAuditGapLogger() { }

    public static void accessDenied(String actorId) {
        LOG.atWarn().addKeyValue("action", "SECURITY_ACCESS_DENIED")
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("user_id", "anonymous".equals(actorId) ? null : HttpRequestLog.maskedUserId(actorId))
                .log("SECURITY_AUDIT_GAP");
    }
}
