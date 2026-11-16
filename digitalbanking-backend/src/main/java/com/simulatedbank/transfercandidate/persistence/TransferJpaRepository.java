package com.simulatedbank.transfercandidate.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal repository; use TransferTerminalJpaAdapter for bulk updates so its managed entity is refreshed. */
public interface TransferJpaRepository extends Repository<TransferEntity, Long> {
    @Transactional(propagation = Propagation.MANDATORY)
    TransferEntity saveAndFlush(TransferEntity entity);
    Optional<TransferEntity> findById(Long id);
    Optional<TransferEntity> findByReference(String reference);

    /** T2 claim token and epoch must match; caller holds guard and locks. One updated row means transition succeeded; zero means ineligible or stale. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("""
            update TransferEntity t
               set t.status = com.simulatedbank.banking.transfer.domain.TransferStatus.COMPLETED,
                   t.completedAt = :at, t.failedAt = null, t.failureCode = null,
                   t.failureMessage = null, t.updatedAt = :at
             where t.id = :id and t.status = com.simulatedbank.banking.transfer.domain.TransferStatus.PENDING
                   and t.attemptState = 'CLAIMED' and t.attemptEpoch = :epoch and t.attemptToken = :token
                   and :at >= t.createdAt
            """)
    int completePending(@Param("id") long transferId, @Param("epoch") long epoch, @Param("token") UUID token,
                        @Param("at") Instant terminalAt);

    /** Update count must be checked. A failure code must be nonblank and fit the database column. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("""
            update TransferEntity t
               set t.status = com.simulatedbank.banking.transfer.domain.TransferStatus.FAILED,
                   t.failedAt = :at, t.completedAt = null, t.failureCode = :code,
                   t.failureMessage = null, t.updatedAt = :at
             where t.id = :id and t.status = com.simulatedbank.banking.transfer.domain.TransferStatus.PENDING
                   and t.attemptState = 'CLAIMED' and t.attemptEpoch = :epoch and t.attemptToken = :token
                   and :at >= t.createdAt
                   and length(trim(:code)) between 1 and 32
            """)
    int failPending(@Param("id") long transferId, @Param("epoch") long epoch, @Param("token") UUID token,
                    @Param("at") Instant terminalAt, @Param("code") String failureCode);
}
