package com.simulatedbank.banking.identity.api;

public interface IdentityRoleCommandPort {
    void apply(RoleChange command);

    record RoleChange(Operation operation, long targetUserId, String code, long changedByUserId) {
    }

    enum Operation {
        ASSIGN_ROLE,
        REVOKE_ROLE,
        GRANT_PRIVILEGE,
        REVOKE_PRIVILEGE
    }
}
