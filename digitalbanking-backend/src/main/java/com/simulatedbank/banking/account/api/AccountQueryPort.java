package com.simulatedbank.banking.account.api;

import java.math.BigDecimal;

public interface AccountQueryPort {
    AccountSnapshot getAccountOrThrow(long accountId);
    AccountSnapshot resolveByAccountNumber(String rawAccountNumber);
    void assertOwnership(long accountId, long customerId);

    record AccountSnapshot(long accountId, long customerId, String accountNumberLast4, String accountType,
            String currency, String status, BigDecimal availableBalance, BigDecimal ledgerBalance) {
    }
}
