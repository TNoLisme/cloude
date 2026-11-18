package com.simulatedbank.banking.limit.adapter.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LimitUsageJpaRepository extends JpaRepository<LimitUsageEntity, Long> {
    @Query(value = """
        SELECT * FROM banking.limit_usage WHERE customer_id=:customer
          AND account_id IS NOT DISTINCT FROM CAST(:account AS bigint) AND usage_date=:date
        """, nativeQuery = true)
    Optional<LimitUsageEntity> usage(@Param("customer") long customer, @Param("account") Long account, @Param("date") LocalDate date);
    @Query(value = """
        SELECT * FROM banking.limit_usage WHERE customer_id=:customer
          AND account_id IS NOT DISTINCT FROM CAST(:account AS bigint) AND usage_date=:date FOR UPDATE
        """, nativeQuery = true)
    List<LimitUsageEntity> lockUsage(@Param("customer") long customer, @Param("account") Long account, @Param("date") LocalDate date);
    @Modifying
    @Query(value = """
        INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (:customer,NULL,:date)
        ON CONFLICT (customer_id,usage_date) WHERE account_id IS NULL DO NOTHING
        """, nativeQuery = true)
    int createCustomerUsage(@Param("customer") long customer, @Param("date") LocalDate date);
    @Modifying
    @Query(value = """
        INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (:customer,:account,:date)
        ON CONFLICT (account_id,usage_date) WHERE account_id IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    int createAccountUsage(@Param("customer") long customer, @Param("account") long account, @Param("date") LocalDate date);
    @Modifying
    @Query(value = """
        UPDATE banking.limit_usage SET used_amount=used_amount+:amount, transfer_count=transfer_count+1, updated_at=now()
        WHERE usage_id=:id AND customer_id=:customer AND account_id IS NOT DISTINCT FROM CAST(:account AS bigint)
          AND usage_date=:date AND (CAST(:daily AS numeric) IS NULL OR used_amount+:amount<=:daily)
        """, nativeQuery = true)
    int increment(@Param("id") long id, @Param("customer") long customer, @Param("account") Long account,
        @Param("date") LocalDate date, @Param("amount") BigDecimal amount, @Param("daily") BigDecimal daily);
}
