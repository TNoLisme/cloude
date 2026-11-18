package com.simulatedbank.banking.common.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allowlisted fallback evidence for C3 security decisions when durable audit is unavailable. */
public final class SecurityAuditFallbackLog {
    public static final String EVENT = "SECURITY_AUDIT_FALLBACK";
    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditFallbackLog.class);

    private SecurityAuditFallbackLog() { }

    public enum Action { SECURITY_ACCESS_DENIED, SECURITY_IDEMPOTENCY_CONFLICT }

    public static void rejected(Action action, long userId) {
        LOG.atWarn()
                .addKeyValue("action", action.name())
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("user_id", HttpRequestLog.maskedUserId(Long.toString(userId)))
                .log(EVENT);
    }
}
