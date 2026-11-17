package com.simulatedbank.banking.identity.adapter.out;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface AuthorityJpaRepository extends Repository<AuthorityUserEntity, Long> {
    interface Grant {
        String getUserType();
        String getRoleCode();
        String getPermissionCode();
        String getPrivilegeCode();
    }

    @Query("""
            select distinct u.userType as userType, r.code as roleCode,
                   p.code as permissionCode, privilege.code as privilegeCode
              from AuthorityUserEntity u
              left join u.roles r on r.status = 'ACTIVE'
                   and ((u.userType = 'CUSTOMER' and r.code = 'CUSTOMER')
                     or (u.userType = 'STAFF' and r.code in ('OPERATIONS', 'ADMIN', 'AUDITOR')))
              left join r.permissions p on p.status = 'ACTIVE'
              left join u.privileges privilege on privilege.status = 'ACTIVE'
                   and privilege.code = 'CHECKER' and u.userType = 'STAFF'
             where u.id = :userId and u.status = 'ACTIVE'
            """)
    List<Grant> findGrants(@Param("userId") long userId);
}
