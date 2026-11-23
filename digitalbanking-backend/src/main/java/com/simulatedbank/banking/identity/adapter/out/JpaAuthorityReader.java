package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.AuthorityReader;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Fresh server-side snapshot, with user-type isolation even for inconsistent RBAC assignments. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuthorityReader implements AuthorityReader {
    private static final Set<String> CUSTOMER_PERMISSIONS = Set.of(
            "ACCOUNT_READ_SELF", "TRANSACTION_READ_SELF", "TRANSFER_CREATE", "LIMIT_READ_SELF");
    private static final Set<String> STAFF_PERMISSIONS = Set.of(
            "CUSTOMER_READ", "ACCOUNT_READ", "TRANSACTION_READ", "AUDIT_READ", "FRAUD_REVIEW",
            "LIMIT_MAKER", "ROLE_MANAGE", "LIMIT_MANAGE", "RETENTION_MANAGE", "FRAUD_READ", "AUDIT_EXPORT");
    private final AuthorityJpaRepository authorities;

    public JpaAuthorityReader(AuthorityJpaRepository authorities) {
        this.authorities = authorities;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> loadAuthorities(long userId) {
        Set<String> result = new LinkedHashSet<>();
        for (var grant : authorities.findGrants(userId)) {
            if (grant.getRoleCode() != null) {
                result.add("ROLE_" + grant.getRoleCode());
                String permission = grant.getPermissionCode();
                Set<String> permitted = "CUSTOMER".equals(grant.getUserType())
                        ? CUSTOMER_PERMISSIONS : STAFF_PERMISSIONS;
                if (permission != null && permitted.contains(permission)) {
                    result.add(permission);
                }
            }
            if ("STAFF".equals(grant.getUserType()) && "CHECKER".equals(grant.getPrivilegeCode())) {
                result.add("CHECKER");
            }
        }
        return Set.copyOf(result);
    }
}
