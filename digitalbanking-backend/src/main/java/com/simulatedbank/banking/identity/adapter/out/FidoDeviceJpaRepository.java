package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface FidoDeviceJpaRepository extends JpaRepository<FidoDeviceEntity, Long> {
    Optional<FidoDeviceEntity> findByDeviceRefAndUserId(UUID deviceRef, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from FidoDeviceEntity device where device.deviceId = :deviceId and device.userId = :userId")
    Optional<FidoDeviceEntity> lockByIdAndUserId(@Param("deviceId") long deviceId, @Param("userId") long userId);
}
