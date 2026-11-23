package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Configuration provider for a trusted admin caller. All operations require its writable transaction.
 * Caller verifies references, account ownership, permissions, step-up and approval state, and records
 * the approval decision and audit in the same transaction. This provider never writes approval data.
 */
public interface LimitChanges {
    /** Read-only preflight; the mutation revalidates policy under its own transaction. */
    void validateChange(BigDecimal perTransaction, BigDecimal daily, String currency, Instant effectiveFrom);

    LimitConfiguration prepare(Proposal proposal);

    LimitConfiguration approve(long approvalId, long checkerId);

    /** JOIN; null expected version means the proposal expected no effective current configuration. */
    LimitConfiguration approve(long approvalId, long checkerId, Integer expectedCurrentVersion);

    LimitConfiguration reject(long approvalId, long checkerId);

    record Proposal(long customerId, Long accountId, BigDecimal perTransactionLimit,
                    BigDecimal dailyLimit, String currency, Instant effectiveFrom,
                    long makerId, long approvalId) { }
}
