package com.simulatedbank.banking.customer.api;

import java.util.Optional;

/** Candidate minimal existence projection for a staff-authorized admin caller; no PII. */
public interface CustomerQuery {
    Optional<CustomerSnapshot> findById(long customerId);

    record CustomerSnapshot(long customerId) { }
}
