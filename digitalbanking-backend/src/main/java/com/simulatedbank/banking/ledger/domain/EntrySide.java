package com.simulatedbank.banking.ledger.domain;

/** Side of a double-entry line; stored as the VARCHAR strings DEBIT/CREDIT (BE-LED-02 §5.1). */
public enum EntrySide {
    DEBIT,
    CREDIT
}