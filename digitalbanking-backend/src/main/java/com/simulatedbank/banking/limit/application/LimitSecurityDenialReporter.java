package com.simulatedbank.banking.limit.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.audit.api.AccessDeniedAudit;
import com.simulatedbank.banking.common.config.SecurityDenialReporter;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.config.SecurityAuditGapLogger;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LimitSecurityDenialReporter implements SecurityDenialReporter {
    private final ObjectProvider<AccessDeniedAudit> audits;
    private final MeterRegistry metrics;

    @Override
    public void report(Authentication authentication, String path, String reason) {
        report(authentication, path, reason, null);
    }

    public void report(Authentication authentication, String path, String reason, String accountId) {
        if (!"/api/v1/limits".equals(path)) return;
        var context = RequestContextHolder.current().orElse(null);
        String actor = authentication != null && authentication.getPrincipal() instanceof SessionAuthenticator.Session session
                ? session.userId() : "anonymous";
        var event = new AccessDeniedAudit.Event(actor, accountId == null ? "LIMIT" : "ACCOUNT", accountId,
                "SECURITY_ACCESS_DENIED", "REJECTED",
                context == null ? null : context.correlationId(), context == null ? null : context.requestId(), reason);
        try {
            var audit = audits.getIfAvailable();
            if (audit != null) {
                audit.record(event);
                return;
            }
        } catch (RuntimeException failure) {
            // C3 failure never changes the denial response.
        }
        try {
            metrics.counter("banking.audit.security.gap", "action", "SECURITY_ACCESS_DENIED").increment();
        } catch (RuntimeException ignored) {
            // An unavailable metrics backend must not change the authorization result.
        }
        SecurityAuditGapLogger.accessDenied(actor);
    }
}
