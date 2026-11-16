package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;

/** Module-internal Spring Data access to Customer persistence. */
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
interface CustomerJpaRepository extends JpaRepository<CustomerEntity, Long> {

    Optional<CustomerEntity> findByUserId(long userId);

    boolean existsByUserId(long userId);

    boolean existsByCustomerIdAndStatus(long customerId, CustomerStatus status);
}
