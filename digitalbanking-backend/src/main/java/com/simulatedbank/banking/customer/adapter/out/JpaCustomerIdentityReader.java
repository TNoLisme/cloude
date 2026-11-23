package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.application.CustomerIdentityReader;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** JPA adapter that projects the persisted Customer row to the PII-free identity mapping. */
@Repository
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class JpaCustomerIdentityReader implements CustomerIdentityReader {

    private final CustomerJpaRepository repository;

    public JpaCustomerIdentityReader(CustomerJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Identity> findByUserId(long userId) {
        return repository.findByUserId(userId).map(JpaCustomerIdentityReader::toIdentity);
    }

    @Override
    public Optional<Identity> findById(long customerId) {
        return repository.findById(customerId).map(JpaCustomerIdentityReader::toIdentity);
    }

    private static Identity toIdentity(CustomerEntity entity) {
        return new Identity(entity.getCustomerId(), entity.getUserId(), entity.getStatus());
    }
}
