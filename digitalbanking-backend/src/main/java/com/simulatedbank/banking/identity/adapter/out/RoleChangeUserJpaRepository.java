package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface RoleChangeUserJpaRepository extends JpaRepository<RoleChangeUserEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from RoleChangeUserEntity u where u.userId = :userId")
    Optional<RoleChangeUserEntity> lockById(@Param("userId") long userId);
}
