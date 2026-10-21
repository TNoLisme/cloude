package com.bank.simulator.transfer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound port to the account module. The transfer module only talks to
 * accounts through this interface; the adapter lives in transfer infrastructure.
 */
public interface AccountModuleApi {

    AccountView findByAccountNumber(String fullAccountNumber);

    LockedAccountPair lockAccountsAscending(UUID accountA, UUID accountB);

    void debit(UUID accountId, BigDecimal amount);

    void credit(UUID accountId, BigDecimal amount);

    record AccountView(UUID accountId, UUID ownerCustomerId, UUID ownerUserId, String accountNumberMasked,
                       String recipientDisplayName, String currency, String status) {}

    record LockedAccount(UUID id, UUID customerId, String status, String balance, String currency) {}

    record LockedAccountPair(LockedAccount source, LockedAccount destination) {}
}
