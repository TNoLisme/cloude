package com.simulatedbank.banking.limit.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface LimitReadPort {
    record Row(Long limitId, Long accountId, BigDecimal perTransactionLimit, BigDecimal dailyLimit,
            String currency, BigDecimal usedToday, Instant effectiveFrom, Instant effectiveTo, Integer version) { }
    List<Row> findSnapshot(long customerId, Long accountId, LocalDate usageDate, Instant asOf);
}
