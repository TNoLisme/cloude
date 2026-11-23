package com.simulatedbank.banking.common.config;

import java.util.Set;

/** The staff role and permission gate for exact-ID Customer and Account reads. */
public final class StaffReadAccess {

    private static final Set<String> STAFF_ROLES = Set.of("ROLE_OPERATIONS", "ROLE_ADMIN", "ROLE_AUDITOR");

    private StaffReadAccess() {}

    public static boolean allows(Set<String> authorities, String permission) {
        return authorities != null && authorities.contains(permission)
                && !authorities.contains("ROLE_CUSTOMER")
                && STAFF_ROLES.stream().anyMatch(authorities::contains);
    }
}
