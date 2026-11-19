package com.simulatedbank.banking.limit.adapter.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LimitConfigJpaRepository extends JpaRepository<LimitConfigEntity, Long> {
    @Query(value = """
        SELECT * FROM banking.limit_config WHERE customer_id=:customer AND (account_id IS NULL OR account_id=:account)
          AND (status='ACTIVE' OR (status='SUPERSEDED' AND effective_to IS NOT NULL)) AND effective_from<=:asOf AND (effective_to IS NULL OR effective_to>:asOf)
        ORDER BY account_id NULLS FIRST
        """, nativeQuery = true)
    List<LimitConfigEntity> effective(@Param("customer") long customer, @Param("account") long account, @Param("asOf") Instant asOf);

    @Query(value = """
        SELECT * FROM banking.limit_config WHERE customer_id=:customer AND (account_id IS NULL OR account_id=:account)
          AND (status='ACTIVE' OR (status='SUPERSEDED' AND (
            (effective_from<=:asOf AND effective_to>:asOf) OR account_id IS NOT NULL)))
        ORDER BY account_id NULLS FIRST
        """, nativeQuery = true)
    List<LimitConfigEntity> transferConfigurations(@Param("customer") long customer, @Param("account") long account,
            @Param("asOf") Instant asOf);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM banking.limit_config WHERE customer_id=:customer
          AND (account_id IS NULL OR account_id=:account) AND status='ACTIVE' AND effective_from>:asOf
          AND effective_from<=clock_timestamp() AND (effective_to IS NULL OR effective_to>clock_timestamp()))
        """, nativeQuery = true)
    boolean hasActivationAfter(@Param("customer") long customer, @Param("account") long account, @Param("asOf") Instant asOf);

    @Query(value = """
        SELECT * FROM banking.limit_config WHERE account_id IS NOT DISTINCT FROM CAST(:account AS bigint)
          AND (account_id IS NOT NULL OR customer_id=:customer) ORDER BY version
        """, nativeQuery = true)
    List<LimitConfigEntity> scope(@Param("customer") long customer, @Param("account") Long account);

    @Query(value = "SELECT * FROM banking.limit_config WHERE approval_id=:approval", nativeQuery = true)
    Optional<LimitConfigEntity> proposal(@Param("approval") long approval);

    @Query(value = "SELECT * FROM banking.limit_config WHERE approval_id=:approval FOR UPDATE", nativeQuery = true)
    Optional<LimitConfigEntity> lockProposal(@Param("approval") long approval);

    @Modifying
    @Query(value = """
        INSERT INTO banking.limit_config(customer_id,account_id,per_transaction_limit,daily_limit,currency,
          effective_from,effective_to,status,version,created_by,approved_by,approval_id,created_at,approved_at,updated_at)
        VALUES (:customer,:account,:perTransaction,:daily,:currency,:effectiveFrom,:effectiveTo,:status,:version,
          :createdBy,:approvedBy,:approval,COALESCE(CAST(:createdAt AS timestamptz),now()),:approvedAt,
          COALESCE(CAST(:updatedAt AS timestamptz),now()))
        ON CONFLICT (approval_id) WHERE approval_id IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    int insertProposal(@Param("customer") long customer, @Param("account") Long account,
        @Param("perTransaction") BigDecimal perTransaction, @Param("daily") BigDecimal daily,
        @Param("currency") String currency, @Param("effectiveFrom") Instant effectiveFrom,
        @Param("effectiveTo") Instant effectiveTo, @Param("status") String status, @Param("version") int version,
        @Param("createdBy") long createdBy, @Param("approvedBy") Long approvedBy, @Param("approval") Long approval,
        @Param("createdAt") Instant createdAt, @Param("approvedAt") Instant approvedAt, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query(value = """
        UPDATE banking.limit_config SET status='SUPERSEDED', effective_to=LEAST(COALESCE(effective_to,:now),:now), updated_at=:now
        WHERE limit_id=:id AND status='ACTIVE'
        """, nativeQuery = true)
    int supersede(@Param("id") long id, @Param("now") Instant now);

    @Modifying
    @Query(value = """
        UPDATE banking.limit_config SET status=:status,
          effective_from=CASE WHEN :approve THEN :now ELSE effective_from END,
          effective_to=CASE WHEN :approve THEN NULL ELSE effective_to END,
          approved_by=:checker, approved_at=:approvedAt, updated_at=:now
        WHERE limit_id=:id AND status='PENDING_APPROVAL'
        """, nativeQuery = true)
    int decide(@Param("id") long id, @Param("status") String status, @Param("approve") boolean approve,
        @Param("checker") Long checker, @Param("approvedAt") Instant approvedAt, @Param("now") Instant now);

    @Query(value = "SELECT transaction_timestamp()", nativeQuery = true)
    Instant transactionTimestamp();
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant clockTimestamp();
    @Query(value = "SHOW transaction_isolation", nativeQuery = true)
    String isolation();
}
