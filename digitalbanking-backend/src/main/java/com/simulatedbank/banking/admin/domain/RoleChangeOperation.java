package com.simulatedbank.banking.admin.domain;

/** Role/privilege operations accepted by API-ROL-01 (07 §8.8.1). */
public enum RoleChangeOperation {
    ASSIGN_ROLE,
    REVOKE_ROLE,
    GRANT_PRIVILEGE,
    REVOKE_PRIVILEGE
}
