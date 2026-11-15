package com.simulatedbank.banking.admin.application;

import java.util.Map;

/**
 * Admin-owned outbound SPI. A production adapter must delegate to the audit
 * owner's transactional/standalone API once that API is available.
 */
public interface ApprovalAuditSink {
    void recordInTransaction(AuditEvent event);

    void recordStandalone(AuditEvent event);

    record AuditEvent(long actorUserId, String action, String entityType, String entityId,
            String outcome, String correlationId, Map<String, Object> safeDetails) {
        public AuditEvent {
            safeDetails = Map.copyOf(safeDetails);
        }
    }
}
