package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable daily scope counter snapshot; enforcement owns locking and increments. */
public record LimitUsage(
        Long usageId, long customerId, Long accountId, LocalDate usageDate,
        String currency, BigDecimal usedAmount, int transferCount, Instant updatedAt
) {
}
