package com.simulatedbank.banking.ledger.domain;

import java.math.BigDecimal;

/** A validated entry line ready to append; entry_id is assigned by the database. */
public record EntryDraft(long transferId, long accountId, int seq, EntrySide side, BigDecimal amount,
                         String currency, BigDecimal balanceAfter, String correlationId) {
}