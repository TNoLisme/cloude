package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Daily scope counter; E1 does not increment it or decide the business timezone. */
public record LimitUsage(
        Long usageId, long customerId, Long accountId, LocalDate usageDate,
        String currency, BigDecimal usedAmount, int transferCount, Instant updatedAt
) {
}
