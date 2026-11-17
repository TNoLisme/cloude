package com.simulatedbank.banking.ledger.domain;

/**
 * The read-back pair violated a posting invariant (count, IDs, seq/type/account, amount,
 * currency, snapshot, correlation or signed sum). Propagates to roll back T2; never replayed
 * or repaired. Common maps it to 500 INTERNAL_ERROR. Messages carry no PII or raw values.
 */
public class LedgerInvariantException extends RuntimeException {

    public LedgerInvariantException(String safeMessage) {
        super(safeMessage);
    }
}