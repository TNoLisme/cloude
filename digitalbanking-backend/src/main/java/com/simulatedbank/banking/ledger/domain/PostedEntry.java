package com.simulatedbank.banking.ledger.domain;

import java.math.BigDecimal;

/** An immutable ledger entry read back from the database after flush (BE-LED-03 §7.2–7.3). */
public record PostedEntry(long entryId, long transferId, long accountId, int seq, EntrySide side,
                          BigDecimal amount, String currency, BigDecimal balanceAfter, String correlationId) {
}