package com.simulatedbank.banking.account.api;

import com.simulatedbank.banking.account.api.AccountBalance.BalanceAfter;
import java.math.BigDecimal;
import java.util.Optional;

/** Account's paired balance mutation witness. Both operations join the writable primary transaction. */
public interface AccountTransferEvidence {
    /** After both conditional updates have applied in T2, write their returned balances before posting Ledger. */
    void recordMovement(Movement movement);

    /** Internal reconciliation read. Empty means a successful read found no witness. */
    Optional<Movement> findMovement(long transferId);

    record Movement(long transferId, long sourceId, long destinationId, BigDecimal amount, String currency,
                    BalanceAfter sourceAfter, BalanceAfter destinationAfter) { }
}
