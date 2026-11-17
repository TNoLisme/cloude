package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface RoleChangeRoleJpaRepository extends JpaRepository<RoleChangeRoleEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from RoleChangeRoleEntity r where r.roleCode = :roleCode")
    Optional<RoleChangeRoleEntity> lockByCode(@Param("roleCode") String roleCode);
}
