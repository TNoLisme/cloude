package com.simulatedbank.banking.ledger.adapter.out.persistence;

import java.math.BigDecimal;

/** Scalar read-back projection so the pair check observes database columns, not managed entities. */
public interface LedgerEntryRow {

    long getEntryId();

    long getTransferId();

    long getAccountId();

    int getEntrySeq();

    String getEntryType();

    BigDecimal getAmount();

    String getCurrency();

    BigDecimal getBalanceAfter();

    String getCorrelationId();
}