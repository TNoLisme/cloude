package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.util.Optional;

/**
 * Read-only persistence port for the minimal Customer identity mapping (BE-CUS-IMP-04).
 *
 * <p>No PII plaintext or ciphertext crosses this boundary — only identifiers and status, so
 * {@code CustomerQuery} never loads encrypted profile columns for a mapping lookup. Backed by the
 * single {@code CustomerJpaRepository} source of truth; INACTIVE rows are preserved as-is because
 * existence and operational eligibility must stay distinguishable (CUS-OI-06).</p>
 */
public interface CustomerIdentityReader {

    /** Minimal persisted mapping; no PII fields. */
    record Identity(long customerId, long userId, CustomerStatus status) {
    }

    Optional<Identity> findByUserId(long userId);

    /** Exact-ID lookup used by the operational `customerId -> userId/status` mapping (no PII loaded). */
    Optional<Identity> findById(long customerId);
}
