package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Internal snapshot of a source 04 section 4.5.1 configuration version. */
public record LimitConfiguration(
        Long limitId, long customerId, Long accountId,
        BigDecimal perTransactionLimit, BigDecimal dailyLimit, String currency,
        Instant effectiveFrom, Instant effectiveTo, Status status, int version,
        long createdBy, Long approvedBy, Long approvalId,
        Instant createdAt, Instant approvedAt, Instant updatedAt
) {
    public enum Status { PENDING_APPROVAL, ACTIVE, SUPERSEDED, REJECTED }
}
