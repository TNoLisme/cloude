package com.simulatedbank.banking.account.api;

import java.util.Optional;

/** Candidate projection; caller checks the returned customer against the requested scope. */
public interface AccountQuery {
    Optional<AccountSnapshot> findById(long accountId);

    record AccountSnapshot(long accountId, long customerId, String currency) { }
}
