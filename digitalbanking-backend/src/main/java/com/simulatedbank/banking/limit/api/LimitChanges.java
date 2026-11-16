package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Configuration provider for a trusted admin caller. All operations require its writable transaction.
 * Caller verifies references, account ownership, permissions, step-up and approval state, and records
 * the approval decision and audit in the same transaction. This provider never writes approval data.
 */
public interface LimitChanges {
    LimitConfiguration prepare(Proposal proposal);

    LimitConfiguration approve(long approvalId, long checkerId);

    LimitConfiguration reject(long approvalId, long checkerId);

    record Proposal(long customerId, Long accountId, BigDecimal perTransactionLimit,
                    BigDecimal dailyLimit, String currency, Instant effectiveFrom,
                    long makerId, long approvalId) { }
}
