package com.simulatedbank.banking.audit.api;

/** Public, provider-owned approval audit facts; no free text, credentials or consumer DTOs. */
public interface AuditModuleApi {
    /** JOIN/C2, fail closed: append failure rolls back the caller transaction. */
    void recordInTransaction(ApprovalEvent event);

    /** Independent C3; append failure falls back to security log/metric and never allows the action. */
    void recordDenied(DeniedEvent event);

    record ApprovalEvent(String action, long approvalId, long actorId, String actorRole,
                         String targetType, String targetId, long makerId, Long checkerId,
                         String decision, String beforeStatus, String status,
                         String correlationId, String requestId) { }

    record DeniedEvent(long actorId, String actorRole, long approvalId,
                       String reasonCode, String correlationId, String requestId) { }
}
