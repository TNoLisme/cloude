package com.simulatedbank.banking.account.api;

import java.math.BigDecimal;
import java.util.List;

public interface AccountBalancePort {
    List<AccountQueryPort.AccountSnapshot> lockTransferAccountsInOrder(long sourceAccountId, long destinationAccountId);
    Balance debitConditional(long sourceAccountId, BigDecimal amount);
    Balance creditConditional(long destinationAccountId, BigDecimal amount);

    record Balance(BigDecimal availableBalance, BigDecimal ledgerBalance) {
    }
}
