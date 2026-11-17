package com.simulatedbank.banking.limit.adapter.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LimitReadRepository extends Repository<LimitConfigEntity, Long> {
    interface Row {
        Long getLimitId();
        Long getAccountId();
        BigDecimal getPerTransactionLimit();
        BigDecimal getDailyLimit();
        String getCurrency();
        BigDecimal getUsedToday();
        Instant getEffectiveFrom();
        Instant getEffectiveTo();
        Integer getVersion();
    }

    @Query(value = """
            SELECT c.limit_id AS "limitId", c.account_id AS "accountId",
                   c.per_transaction_limit AS "perTransactionLimit", c.daily_limit AS "dailyLimit",
                   c.currency AS "currency", COALESCE(u.used_amount, 0.00) AS "usedToday",
                   c.effective_from AS "effectiveFrom", c.effective_to AS "effectiveTo", c.version AS "version"
            FROM banking.limit_config c
            LEFT JOIN banking.limit_usage u ON u.customer_id = c.customer_id
              AND u.account_id IS NOT DISTINCT FROM c.account_id AND u.usage_date = :usageDate
            WHERE c.customer_id = :customerId
              AND (c.account_id IS NULL OR c.account_id = :accountId)
              AND c.status = 'ACTIVE' AND c.effective_from <= :asOf
              AND (c.effective_to IS NULL OR c.effective_to > :asOf)
            ORDER BY c.account_id NULLS FIRST
            """, nativeQuery = true)
    List<Row> findSnapshot(@Param("customerId") long customerId, @Param("accountId") Long accountId,
            @Param("usageDate") LocalDate usageDate, @Param("asOf") Instant asOf);
}
