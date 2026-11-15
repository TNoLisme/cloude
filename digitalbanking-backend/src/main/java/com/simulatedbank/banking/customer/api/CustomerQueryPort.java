package com.simulatedbank.banking.customer.api;

public interface CustomerQueryPort {
    CustomerSnapshot getCustomerByUserId(long userId);
    CustomerSnapshot getCustomerById(long customerId);
    void assertCustomerActiveAndKycVerified(long customerId);

    record CustomerSnapshot(long customerId, long userId, String cifNumber, String fullName,
            String kycStatus, String status) {
    }
}
