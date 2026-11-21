package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Resolves the server-side Customer snapshot shared by login and request authentication. */
@Component
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class SessionCustomerBinding {

    private final CustomerQuery customers;

    public SessionCustomerBinding(CustomerQuery customers) {
        this.customers = customers;
    }

    public String resolve(long userId, String userType) {
        if (!"CUSTOMER".equals(userType)) {
            return null;
        }
        try {
            return customers.findByUserId(userId)
                    .map(identity -> Long.toString(identity.customerId()))
                    .orElse(null);
        } catch (DataAccessException | TransactionException storageFailure) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
