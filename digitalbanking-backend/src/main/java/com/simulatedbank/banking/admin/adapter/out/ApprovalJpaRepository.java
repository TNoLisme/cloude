package com.simulatedbank.banking.admin.adapter.out;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ApprovalJpaRepository extends JpaRepository<ApprovalEntity, Long> {
    @Query(value = "SELECT * FROM banking.approval_request WHERE approval_id=:id AND request_type='LIMIT_CHANGE'", nativeQuery = true)
    Optional<ApprovalEntity> findLimitChange(@Param("id") long id);
    @Query(value = "SELECT * FROM banking.approval_request WHERE approval_id=:id AND request_type='LIMIT_CHANGE' FOR UPDATE", nativeQuery = true)
    Optional<ApprovalEntity> lockLimitChange(@Param("id") long id);
    @Modifying
    @Query(value = "UPDATE banking.approval_request SET payload=CAST(:payload AS jsonb) WHERE approval_id=:id AND status='PENDING'", nativeQuery = true)
    int setPayload(@Param("id") long id, @Param("payload") String payload);
    @Modifying
    @Query(value = """
        UPDATE banking.approval_request SET status=:status,checker_id=:checker,decision_note=:note,
          decided_at=clock_timestamp(), applied_at=CASE WHEN :approve THEN clock_timestamp() ELSE NULL END
        WHERE approval_id=:id AND request_type='LIMIT_CHANGE' AND status='PENDING'
        """, nativeQuery = true)
    int decide(@Param("id") long id, @Param("status") String status, @Param("checker") long checker,
            @Param("note") String note, @Param("approve") boolean approve);
}
