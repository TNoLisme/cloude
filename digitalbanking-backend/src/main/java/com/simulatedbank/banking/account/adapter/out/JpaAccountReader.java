package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountReadPort;
import com.simulatedbank.banking.account.domain.AccountStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** JPA adapter chuyển {@link AccountEntity} sang projection đọc không chứa ciphertext/blind index. */
@Repository
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAccountReader implements AccountReadPort {

    private final AccountJpaRepository repository;

    public JpaAccountReader(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AccountRecord> findById(long accountId) {
        return repository.findById(accountId).map(JpaAccountReader::toRecord);
    }

    @Override
    public Optional<AccountRecord> findByAccountNumberHash(String accountNumberHash) {
        return repository.findByAccountNumberHash(accountNumberHash).map(JpaAccountReader::toRecord);
    }

    @Override
    public List<AccountRecord> findByCustomerId(long customerId, AccountStatus statusFilter) {
        List<AccountEntity> accounts = statusFilter == null
                ? repository.findByCustomerIdOrderByAccountIdAsc(customerId)
                : repository.findByCustomerIdAndStatusOrderByAccountIdAsc(customerId, statusFilter);
        return accounts.stream().map(JpaAccountReader::toRecord).toList();
    }

    private static AccountRecord toRecord(AccountEntity entity) {
        return new AccountRecord(
                entity.getAccountId(),
                entity.getCustomerId(),
                entity.getStatus(),
                entity.getCurrency(),
                entity.getAvailableBalance(),
                entity.getLedgerBalance(),
                entity.getAccountNumberLast4(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
