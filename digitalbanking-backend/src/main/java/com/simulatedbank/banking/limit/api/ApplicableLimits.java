package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record ApplicableLimits(long customerId, long sourceAccountId, Instant asOf,
        List<LimitConfiguration> items) {

    public ApplicableLimits {
        items = List.copyOf(items);
    }

    public Optional<BigDecimal> effectivePerTransactionLimit() {
        return items.stream().map(LimitConfiguration::perTransactionLimit).min(BigDecimal::compareTo);
    }
}
