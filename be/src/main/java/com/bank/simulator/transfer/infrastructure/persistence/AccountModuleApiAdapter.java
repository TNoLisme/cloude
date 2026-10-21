package com.bank.simulator.transfer.infrastructure.persistence;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.transfer.api.AccountModuleApi;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class AccountModuleApiAdapter implements AccountModuleApi {
    private final AccountJdbcRepository accounts;
    private final IdentityJdbcRepository identities;
    private final TransferLockRepository locks;

    public AccountModuleApiAdapter(AccountJdbcRepository accounts, IdentityJdbcRepository identities,
                                   TransferLockRepository locks) {
        this.accounts = accounts;
        this.identities = identities;
        this.locks = locks;
    }

    @Override
    public AccountView findByAccountNumber(String fullAccountNumber) {
        AccountJdbcRepository.AccountRow row = accounts.findByAccountNumber(fullAccountNumber);
        if (row == null) return null;
        var customer = identities.findCustomerById(row.customerId());
        String displayName = customer == null ? null : customer.fullName();
        return new AccountView(row.id(), row.customerId(), customer == null ? null : customer.userId(),
                row.maskedNumber(), displayName == null || displayName.isBlank() ? "Customer" : displayName,
                row.currency(), row.status());
    }

    @Override
    public LockedAccountPair lockAccountsAscending(UUID accountA, UUID accountB) {
        if (accountA == null || accountB == null || accountA.equals(accountB)) return null;
        List<TransferLockRepository.LockedAccountRow> rows = locks.lockAccountsAscending(accountA, accountB);
        if (rows.size() != 2) return null;
        LockedAccount first = new LockedAccount(rows.get(0).id(), rows.get(0).customerId(), rows.get(0).status(),
                rows.get(0).balance(), rows.get(0).currency());
        LockedAccount second = new LockedAccount(rows.get(1).id(), rows.get(1).customerId(), rows.get(1).status(),
                rows.get(1).balance(), rows.get(1).currency());
        return first.id().equals(accountA) ? new LockedAccountPair(first, second) : new LockedAccountPair(second, first);
    }

    @Override
    public void debit(UUID accountId, BigDecimal amount) {
        accounts.debit(accountId, amount.toPlainString());
    }

    @Override
    public void credit(UUID accountId, BigDecimal amount) {
        accounts.credit(accountId, amount.toPlainString());
    }
}
