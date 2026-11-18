package com.simulatedbank.banking.notification.adapter.out.persistence;

import com.simulatedbank.banking.notification.domain.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    List<NotificationEntity> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<NotificationEntity> findByTransferId(Long transferId);

    @Query("SELECT COUNT(n) FROM NotificationEntity n WHERE n.status IN :statuses")
    long countByStatusIn(@Param("statuses") Collection<NotificationStatus> statuses);
}
