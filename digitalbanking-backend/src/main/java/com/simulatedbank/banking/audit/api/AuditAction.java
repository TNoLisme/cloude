package com.simulatedbank.banking.audit.api;

/**
 * Stable audit action catalog (design/01 §5.3). Action codes never change meaning; every new action must be
 * versioned in the catalog and covered by tests. The criticality class is a property of the action, not of the
 * caller, so a consumer cannot downgrade a C2 event to a best-effort write.
 */
public enum AuditAction {
    AUTH_LOGIN_SUCCESS(AuditClass.C2),
    AUTH_LOGIN_FAILED(AuditClass.C3),
    AUTH_ACCOUNT_LOCKED(AuditClass.C3),
    AUTH_SESSION_LOGOUT(AuditClass.C3),
    AUTH_CHALLENGE_CREATED(AuditClass.C2),
    AUTH_CHALLENGE_RESENT(AuditClass.C2),
    AUTH_CHALLENGE_LOCKED(AuditClass.C3),
    AUTH_STEP_UP_VERIFIED(AuditClass.C2),
    AUTH_STEP_UP_FAILED(AuditClass.C3),
    TRANSFER_CREATED(AuditClass.C1),
    TRANSFER_COMPLETED(AuditClass.C1),
    TRANSFER_FAILED(AuditClass.C1),
    TRANSFER_RECONCILED(AuditClass.C1),
    SECURITY_IDEMPOTENCY_CONFLICT(AuditClass.C3),
    LIMIT_CHANGE_REQUESTED(AuditClass.C2),
    LIMIT_CHANGE_APPROVED(AuditClass.C2),
    LIMIT_CHANGE_REJECTED(AuditClass.C2),
    ROLE_CHANGE_REQUESTED(AuditClass.C2),
    ROLE_CHANGE_APPROVED(AuditClass.C2),
    ROLE_CHANGE_REJECTED(AuditClass.C2),
    ACCOUNT_STATUS_CHANGED(AuditClass.C2),
    FRAUD_FLAG_CREATED(AuditClass.C2),
    FRAUD_FLAG_REVIEWED(AuditClass.C2),
    FRAUD_ENGINE_ERROR(AuditClass.C4),
    LEGAL_HOLD_PLACED(AuditClass.C2),
    LEGAL_HOLD_RELEASED(AuditClass.C2),
    RETENTION_POLICY_CHANGED(AuditClass.C2),
    RETENTION_ARCHIVED(AuditClass.C4),
    RETENTION_PURGED(AuditClass.C2),
    AUDIT_EXPORTED(AuditClass.C2),
    SECURITY_ACCESS_DENIED(AuditClass.C3);

    private final AuditClass auditClass;

    AuditAction(AuditClass auditClass) {
        this.auditClass = auditClass;
    }

    public AuditClass auditClass() {
        return auditClass;
    }
}
