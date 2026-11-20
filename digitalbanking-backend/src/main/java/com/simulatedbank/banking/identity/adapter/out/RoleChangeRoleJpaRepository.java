package com.simulatedbank.banking.identity.adapter.out;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface RoleChangeRoleJpaRepository extends JpaRepository<RoleChangeRoleEntity, Long> {
    // V23 takes FOR SHARE without granting the runtime caller UPDATE on the role catalogue.
    @Query(value = "select * from banking.lock_role_for_assignment(:roleCode)", nativeQuery = true)
    Optional<RoleChangeRoleEntity> lockByCode(@Param("roleCode") String roleCode);
}
