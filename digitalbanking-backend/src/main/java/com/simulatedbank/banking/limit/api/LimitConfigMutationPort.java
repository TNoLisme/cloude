package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider-owned contract for the Admin Maker/Checker limit flow (docs-common/design/05 §10,
 * docs-common/04 §4.5.1). Limit owns the configuration rows, policy/version validation and the
 * PENDING_APPROVAL → ACTIVE/SUPERSEDED/REJECTED transitions; Admin owns the approval aggregate, permissions
 * and the final C2 audit.
 *
 * <p>All mutating operations are {@code Propagation.MANDATORY}: they join the Admin approval transaction, so
 * the approval status, the limit configuration and the audit record share one commit or one rollback. Error
 * conventions are the shared catalog: {@code CUSTOMER_NOT_FOUND}/{@code ACCOUNT_NOT_FOUND} (404),
 * {@code LIMIT_OUT_OF_POLICY} (422), {@code ROLE_CONFLICT} for a stale expected version (409) and
 * {@code INVALID_STATE_TRANSITION} for an unexpected pending state (409).</p>
 */
public interface LimitConfigMutationPort {

    /**
     * Base version captured before the Maker step-up challenge. {@code 0} means the scope has no ACTIVE
     * configuration yet (first version). Pure read; anonymous structural validation only.
     */
    long currentVersion(long customerId, Long accountId);

    /**
     * Creates the {@code PENDING_APPROVAL} row of the approval (version {@code expectedVersion + 1}) in the
     * proposal transaction. Re-validates that the scope still has exactly the expected ACTIVE version, so a
     * change committed between step-up and this transaction is a conflict, never a lost update.
     *
     * @return identifier and version of the new pending row
     */
    PendingChange reservePendingChange(LimitChangeRequest request);

    /**
     * Applies the approved change: the pending row becomes the new ACTIVE version and the previous ACTIVE row is
     * superseded with its effective interval ending at the cutover, inside the checker transaction. Duplicate application is impossible (the pending row is
     * transitioned once and {@code limit_config.approval_id} is unique).
     */
    ConfigApplied applyApprovedLimitChange(ApprovedLimitChange change);

    /**
     * Marks the pending row {@code REJECTED} in the rejection transaction so no orphan PENDING_APPROVAL row can
     * ever be approved later.
     */
    RejectedLimitChange rejectPendingChange(long approvalId, long checkerUserId, String correlationId);

    /** Proposal-time command; values are validated by Limit before the pending row is written. */
    record LimitChangeRequest(long approvalId, long actorUserId, long customerId, Long accountId,
            BigDecimal perTransactionLimit, BigDecimal dailyLimit, String currency, Instant effectiveFrom,
            long expectedVersion) {
    }

    /** New {@code PENDING_APPROVAL} configuration created by the Maker proposal. */
    record PendingChange(long limitConfigId, long version) {
    }

    /** Checker command; the provider re-reads the persisted pending values, never client-supplied ones. */
    record ApprovedLimitChange(long approvalId, long checkerUserId, long expectedVersion, String correlationId) {
    }

    /** Applied configuration with the previous/new values for the C2 audit state (strings, no PII). */
    record ConfigApplied(long limitConfigId, long version, Instant effectiveFrom, String previousPerTransactionLimit,
            String previousDailyLimit, String perTransactionLimit, String dailyLimit) {
    }

    /** Rejected pending configuration. */
    record RejectedLimitChange(long approvalId, long limitConfigId) {
    }
}
