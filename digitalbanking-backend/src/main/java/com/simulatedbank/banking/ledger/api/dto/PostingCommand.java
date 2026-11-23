package com.simulatedbank.banking.ledger.api.dto;

import java.math.BigDecimal;

/**
 * Immutable posting facts supplied by Transfer from the locked intent and the two Account ledger
 * balance snapshots taken in the same T2 (TRFC-LE-01). All fields are mandatory; the provider
 * validates them before any INSERT. {@code balanceAfter} values are ledger balances, not available
 * balances. {@code correlationId} is the correlation stored with the intent, never regenerated.
 */
public record PostingCommand(long transferId, long sourceId, long destinationId, BigDecimal amount, String currency,
                             BigDecimal sourceBalanceAfter, BigDecimal destinationBalanceAfter, String correlationId) {
}