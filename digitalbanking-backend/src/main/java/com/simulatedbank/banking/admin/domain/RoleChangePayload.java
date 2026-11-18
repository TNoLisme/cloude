package com.simulatedbank.banking.admin.domain;

import java.util.Set;

/**
 * Requested role/privilege change (API-ROL-01, 07 §8.8.1).
 *
 * <p>{@code expectedVersion} is captured from {@code identity.api.IdentityRoleCommand.getRoleAssignmentVersion}
 * at proposal time and persisted here (DEC-ID-011); it is never accepted from the public client.</p>
 */
public record RoleChangePayload(
        RoleChangeOperation operation,
        long targetUserId,
        String roleCode,
        String privilegeCode,
        long expectedVersion,
        String reason
) implements ChangePayload {

    private static final Set<String> APPROVED_ROLE_CODES =
            Set.of("CUSTOMER", "OPERATIONS", "ADMIN", "AUDITOR");
    private static final String APPROVED_PRIVILEGE_CODE = "CHECKER";

    public RoleChangePayload {
        if (operation == null) {
            throw AdminText.invalid();
        }
        AdminText.positiveId(targetUserId);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw AdminText.invalid();
        }
        switch (operation) {
            case ASSIGN_ROLE, REVOKE_ROLE -> {
                if (privilegeCode != null || roleCode == null || !APPROVED_ROLE_CODES.contains(roleCode)) {
                    throw AdminText.invalid();
                }
            }
            case GRANT_PRIVILEGE, REVOKE_PRIVILEGE -> {
                if (roleCode != null || !APPROVED_PRIVILEGE_CODE.equals(privilegeCode)) {
                    throw AdminText.invalid();
                }
            }
        }
        reason = AdminText.optional(reason, 500);
    }

    @Override
    public ApprovalRequestType requestType() {
        return ApprovalRequestType.ROLE_CHANGE;
    }

    @Override
    public ApprovalTargetType targetType() {
        return ApprovalTargetType.APP_USER;
    }

    @Override
    public String targetId() {
        return Long.toString(targetUserId);
    }
}
