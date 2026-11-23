package com.simulatedbank.banking.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable transaction snapshot provided by Transfer caller inside T2.
 */
public record FraudTransferSnapshot(
        Long transferId,
        Long customerId,
        Long sourceAccountId,
        Long destinationAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        int windowTransactionCount,
        boolean isReplay,
        String correlationId
) {
    public FraudTransferSnapshot {
        if (transferId == null) {
            throw new IllegalArgumentException("transferId cannot be null");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId cannot be null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt cannot be null");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be blank");
        }
    }
}
