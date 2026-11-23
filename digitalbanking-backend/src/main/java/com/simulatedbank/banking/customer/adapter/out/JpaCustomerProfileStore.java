package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.application.CustomerProfileStore;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** JPA adapter that accepts and returns ciphertext only. */
@Repository
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class JpaCustomerProfileStore implements CustomerProfileStore {
    private final CustomerJpaRepository repository;

    public JpaCustomerProfileStore(CustomerJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public StoredCustomer create(
            long userId,
            byte[] fullNameEncrypted,
            byte[] phoneEncrypted,
            byte[] emailEncrypted,
            CustomerStatus status) {
        CustomerEntity entity = new CustomerEntity(
                userId, fullNameEncrypted, phoneEncrypted, emailEncrypted, status);
        return map(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<StoredCustomer> findById(long customerId) {
        return repository.findById(customerId).map(JpaCustomerProfileStore::map);
    }

    @Override
    public StoredCustomer replaceEncryptedPii(
            long customerId,
            byte[] fullNameEncrypted,
            byte[] phoneEncrypted,
            byte[] emailEncrypted) {
        CustomerEntity entity = repository.findById(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer disappeared during update"));
        entity.replaceEncryptedPii(fullNameEncrypted, phoneEncrypted, emailEncrypted);
        return map(repository.saveAndFlush(entity));
    }

    private static StoredCustomer map(CustomerEntity entity) {
        return new StoredCustomer(
                entity.getCustomerId(),
                entity.getUserId(),
                entity.getFullNameEncrypted(),
                entity.getPhoneEncrypted(),
                entity.getEmailEncrypted(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
