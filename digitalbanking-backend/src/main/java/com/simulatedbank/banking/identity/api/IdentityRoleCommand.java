package com.simulatedbank.banking.identity.api;

/** Identity-owned command joined to Admin's approval, state transition and final C2 audit transaction. */
public interface IdentityRoleCommand {
    enum Operation { ASSIGN_ROLE, REVOKE_ROLE, GRANT_PRIVILEGE, REVOKE_PRIVILEGE }

    enum ApprovalState { PENDING, APPROVED, REJECTED, APPLIED, CANCELLED }

    record ApprovedRoleChange(long approvalId, long makerUserId, long checkerUserId,
            ApprovalState approvalState, long targetUserId, Operation operation, String roleCode,
            String privilegeCode, long expectedVersion, String correlationId, String requestId) {
    }

    record RoleChangeResult(long approvalId, long targetUserId, Operation operation, String roleCode,
            String privilegeCode, long previousVersion, long resultingVersion, int revokedSessionCount,
            String correlationId, String requestId) {
    }

    RoleChangeResult applyApprovedRoleChange(ApprovedRoleChange command);

    /** Internal proposal-time read; Admin persists this value in the approval payload. */
    long getRoleAssignmentVersion(long targetUserId);
}
