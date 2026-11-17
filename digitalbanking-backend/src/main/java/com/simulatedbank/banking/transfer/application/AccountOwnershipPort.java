package com.simulatedbank.banking.transfer.application;

/**
 * TRFC-AC-02 seam: Account-owned ownership read on the primary. FORBIDDEN must not reveal account
 * data; a database/system error must throw rather than masquerade as NOT_FOUND.
 */
@FunctionalInterface
public interface AccountOwnershipPort {
    Ownership assertOwnership(long customerId, long accountId);

    enum Ownership { OWNED, FORBIDDEN, NOT_FOUND }
}