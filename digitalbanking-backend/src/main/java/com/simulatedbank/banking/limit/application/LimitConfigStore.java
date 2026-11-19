package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.limit.domain.LimitConfig;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port used by {@link LimitConfigMutationService}; implemented by the JDBC adapter.
 *
 * <p>Every {@code lock*} method must issue {@code SELECT ... FOR UPDATE} in the caller's
 * transaction. The port deliberately exposes neither entities nor generic SQL: the service
 * only sees {@link LimitConfig} aggregates and identifiers, so persistence details cannot leak
 * into the application/domain layers.</p>
 */
public interface LimitConfigStore {

    /** Reads the current ACTIVE configuration of the scope without locking (pure read). */
    Optional<LimitConfig> findActive(long customerId, Long accountId);

    /** Reads any configuration row of an approval regardless of status (duplicate detection). */
    Optional<LimitConfig> findByApprovalId(long approvalId);

    /**
     * Locks the scope's ACTIVE row, if any, for the rest of the caller's transaction.
     * Must be called after {@link #lockCustomerScope(long)}.
     */
    Optional<LimitConfig> lockActive(long customerId, Long accountId);

    /** Locks the configuration row of an approval, if any, for the rest of the transaction. */
    Optional<LimitConfig> lockByApprovalId(long approvalId);

    /**
     * Takes the shared PostgreSQL transaction-scoped advisory lock for the customer.
     * The lock serializes version capture between a reservation and a checker apply even when
     * the scope has no ACTIVE row to lock yet (first version), avoiding a unique-index race.
     * Different scopes may occasionally share a key; that only serializes unrelated work.
     */
    void lockCustomerScope(long customerId);

    Instant currentTime();

    /** Inserts the PENDING_APPROVAL row and returns its generated identifier. */
    long insertPending(LimitConfig pending);

    /** {@code ACTIVE -> SUPERSEDED}; returns {@code false} when the row was not ACTIVE. */
    boolean supersede(long limitConfigId, Instant effectiveTo, Instant updatedAt);

    /** {@code PENDING_APPROVAL -> ACTIVE}; returns {@code false} when the row was not pending. */
    boolean activate(long limitConfigId, Instant effectiveFrom, long approvedBy, Instant approvedAt);

    /** {@code PENDING_APPROVAL -> REJECTED}; returns {@code false} when the row was not pending. */
    boolean reject(long limitConfigId, Instant updatedAt);
}
