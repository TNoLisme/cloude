package com.simulatedbank.banking.identity.application;

import java.util.Map;

/**
 * Identity-owned outbound SPI for C3 security events. The audit module adapter is
 * intentionally absent until the audit owner exposes its canonical API.
 */
public interface IdentitySecurityAuditSink {
    void recordStandalone(SecurityEvent event);

    record SecurityEvent(long actorUserId, String action, String entityType, String entityId,
            String outcome, String correlationId, Map<String, Object> safeDetails) {
        public SecurityEvent {
            safeDetails = Map.copyOf(safeDetails);
        }
    }
}
