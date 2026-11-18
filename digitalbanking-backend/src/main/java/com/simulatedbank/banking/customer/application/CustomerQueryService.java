package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application implementor of the public {@link CustomerQuery} contract (BE-CUS-IMP-04).
 *
 * <p>Read-only ({@code R}, design/05 §3.2/§6): never writes, never creates a Customer row, never
 * decrypts PII. Missing mappings return {@code Optional.empty()}; INACTIVE rows keep their status
 * so consumers can enforce eligibility themselves (CUS-OI-06). Technical persistence failures
 * propagate unchanged for transaction rollback — they are never swallowed or reclassified.</p>
 */
@Service
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class CustomerQueryService implements CustomerQuery {

    private final CustomerIdentityReader reader;

    public CustomerQueryService(CustomerIdentityReader reader) {
        this.reader = reader;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerIdentity> findByUserId(long userId) {
        requirePositiveId(userId);
        return reader.findByUserId(userId).map(CustomerQueryService::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerIdentity> findByCustomerId(long customerId) {
        requirePositiveId(customerId);
        return reader.findById(customerId).map(CustomerQueryService::toSnapshot);
    }

    private static void requirePositiveId(long value) {
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static CustomerIdentity toSnapshot(CustomerIdentityReader.Identity identity) {
        return new CustomerIdentity(
                identity.customerId(),
                identity.userId(),
                toPublicStatus(identity.status()));
    }

    private static CustomerQuery.Status toPublicStatus(CustomerStatus status) {
        return switch (status) {
            case ACTIVE -> CustomerQuery.Status.ACTIVE;
            case INACTIVE -> CustomerQuery.Status.INACTIVE;
        };
    }
}
