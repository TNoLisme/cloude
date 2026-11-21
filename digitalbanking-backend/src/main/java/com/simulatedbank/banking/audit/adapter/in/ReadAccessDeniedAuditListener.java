package com.simulatedbank.banking.audit.adapter.in;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.common.config.ReadAccessDenied;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.context.SecurityAuditFallbackLog;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Maps read denials to Audit-owned C3 records without coupling Common to Audit. */
@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class ReadAccessDeniedAuditListener {

    private final AuditModuleApi audit;
    private final Clock clock;

    public ReadAccessDeniedAuditListener(AuditModuleApi audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    public void onReadAccessDenied(ReadAccessDenied denial) {
        String requestId = RequestContextHolder.current().map(RequestContext::requestId).orElse(null);
        var event = new AuditEvent(AuditAction.SECURITY_ACCESS_DENIED, AuditActorType.USER,
                denial.actorUserId(), actorRole(denial.actorRoles()), denial.entityType(),
                denial.entityId() == null ? "UNKNOWN" : denial.entityId(), null,
                Map.of("reasonCode", "AUTH_FORBIDDEN"), null, null, AuditOutcome.REJECTED,
                denial.correlationId(), requestId, null, clock.instant());
        if (audit.recordStandalone(event).isEmpty()) {
            SecurityAuditFallbackLog.rejected(SecurityAuditFallbackLog.Action.SECURITY_ACCESS_DENIED,
                    denial.actorUserId());
        }
    }

    private static String actorRole(String roleCodes) {
        if (roleCodes == null) {
            return "UNKNOWN";
        }
        var roles = Arrays.asList(roleCodes.split(","));
        return roles.contains("CUSTOMER") ? "CUSTOMER"
                : roles.stream().filter(role -> !role.isBlank()).sorted().findFirst().orElse("UNKNOWN");
    }
}
