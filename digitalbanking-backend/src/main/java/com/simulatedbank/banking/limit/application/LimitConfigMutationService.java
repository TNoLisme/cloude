package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import com.simulatedbank.banking.limit.domain.LimitConfig;
import com.simulatedbank.banking.limit.domain.LimitConfigStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider runtime for the Admin Maker/Checker Limit flow
 * ({@code design/05 §10/§12.1}, {@code 07 §8.5.2–8.5.3}, {@code 04 §4.5.1}).
 *
 * <h2>Transaction boundary</h2>
 * <p>All three mutating operations are {@code Propagation.MANDATORY}: they must join the
 * Admin approval transaction so the approval row, the limit configuration and the final C2
 * audit commit or roll back together. The provider never starts its own transaction and never
 * writes Admin state.</p>
 *
 * <h2>Version and concurrency</h2>
 * <p>A scope (customer when {@code accountId == null}, otherwise the account) has at most one
 * ACTIVE row, enforced by the partial unique indexes in V9. The Maker captures
 * {@code expectedVersion} before the step-up challenge ({@code 0} = no ACTIVE row). A
 * reservation re-reads the ACTIVE row under {@code FOR UPDATE} and stores the pending row as
 * version {@code expectedVersion + 1}. A checker apply re-reads the pending row for update,
 * locks the ACTIVE row and requires exactly that version. Any mismatch is
 * {@code ROLE_CONFLICT} (409); the shared error catalog has no limit-specific version-conflict
 * code, so the documented role/version conflict code is reused rather than adding a new one.
 * The customer guard is shared with Transfer and the legacy Limit API, so two first-version
 * applies cannot race past the unique index without a deterministic conflict result.</p>
 *
 * <h2>Duplicate application</h2>
 * <p>{@code limit_config.approval_id} is UNIQUE and the pending row is transitioned by a
 * conditional update in this transaction. Applying the same approval twice therefore finds a
 * row that is no longer {@code PENDING_APPROVAL} and fails with
 * {@code INVALID_STATE_TRANSITION} (409). A rejected pending row can never become ACTIVE.</p>
 *
 * <h2>Policy (OI-API-05)</h2>
 * <p>07 requires new limits to be "within policy" but leaves the numeric bounds unresolved.
 * They are configuration ({@code app.limit.policy.max-per-transaction} /
 * {@code max-daily}); when unset the provider adds no bound. Policy is validated on proposal
 * and re-validated from the persisted pending values on approval, so a bound tightened while
 * the approval is pending still blocks the stale change.</p>
 */
@Service
@ConditionalOnProperty(name = {"app.limit.enabled"}, havingValue = "true", matchIfMissing = true)
public class LimitConfigMutationService implements LimitConfigMutationPort {

    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final String DEFAULT_CURRENCY = "VND";

    private final LimitConfigStore store;
    private final LimitPolicy policy;
    private final CustomerQuery customers;
    private final AccountQuery accounts;

    public LimitConfigMutationService(LimitConfigStore store, LimitPolicy policy, CustomerQuery customers,
            AccountQuery accounts) {
        this.store = store;
        this.policy = policy;
        this.customers = customers;
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public long currentVersion(long customerId, Long accountId) {
        requirePositive(customerId, "customerId");
        if (accountId != null) {
            requirePositive(accountId, "accountId");
        }
        return store.findActive(customerId, accountId).map(config -> (long) config.version()).orElse(0L);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PendingChange reservePendingChange(LimitChangeRequest request) {
        Objects.requireNonNull(request, "request is required");
        // Keep the client value in the step-up hash. Default the persisted proposal timestamp on primary.
        java.time.Instant effectiveFrom = request.effectiveFrom() == null
                ? store.currentTime() : request.effectiveFrom();
        LimitConfig pending = LimitConfig.pending(request.approvalId(), request.actorUserId(),
                request.customerId(), request.accountId(), request.perTransactionLimit(), request.dailyLimit(),
                request.currency() == null ? DEFAULT_CURRENCY : request.currency(), effectiveFrom,
                request.expectedVersion());

        requireCustomerExists(pending.customerId());
        requireAccountOwnership(pending.accountId(), pending.customerId(), approvalCorrelation(request.approvalId()));
        policy.requireWithinPolicy(pending.perTransactionLimit(), pending.dailyLimit());

        store.lockCustomerScope(pending.customerId());
        if (store.findByApprovalId(pending.approvalId()).isPresent()) {
            // One approval may own exactly one configuration row; a retry is not a second proposal.
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        requireExpectedActiveVersion(pending.version() - 1L,
                store.lockActive(pending.customerId(), pending.accountId()));

        try {
            long limitConfigId = store.insertPending(pending);
            return new PendingChange(limitConfigId, pending.version());
        } catch (DuplicateKeyException duplicateApproval) {
            // The unique approval_id is the last line of defence against a concurrent duplicate reservation.
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfigApplied applyApprovedLimitChange(ApprovedLimitChange change) {
        Objects.requireNonNull(change, "change is required");
        requirePositive(change.approvalId(), "approvalId");
        requirePositive(change.checkerUserId(), "checkerUserId");
        LimitConfig.pendingVersion(change.expectedVersion());
        requireCorrelationId(change.correlationId());

        LimitConfig pending = store.findByApprovalId(change.approvalId())
                .orElseThrow(LimitConfigMutationService::invalidPendingState);
        requirePendingState(pending);
        requireCustomerExists(pending.customerId());
        requireAccountOwnership(pending.accountId(), pending.customerId(), change.correlationId());

        store.lockCustomerScope(pending.customerId());
        // Re-read under the lock: the checker transaction is the only place allowed to transition it.
        LimitConfig locked = store.lockByApprovalId(change.approvalId())
                .orElseThrow(LimitConfigMutationService::invalidPendingState);
        requirePendingState(locked);
        Optional<LimitConfig> active = store.lockActive(locked.customerId(), locked.accountId());
        requireExpectedActiveVersion(change.expectedVersion(), active);
        locked.requirePendingVersion(change.expectedVersion());
        policy.requireWithinPolicy(locked.perTransactionLimit(), locked.dailyLimit());

        Instant now = store.currentTime();
        if (active.filter(config -> !config.effectiveFrom().isBefore(now)).isPresent()) {
            // Only one scheduled replacement can be outstanding for a scope.
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
        // Checks happen before writes so an illegal maker/checker pair leaves no partial state.
        LimitConfig activated = locked.activate(change.checkerUserId(), now);
        Instant cutover = activated.effectiveFrom();
        if (active.isPresent() && !store.supersede(active.get().limitConfigId(), cutover, now)) {
            throw new IllegalStateException("ACTIVE limit configuration changed while locked");
        }
        if (!store.activate(activated.limitConfigId(), cutover, change.checkerUserId(), now)) {
            throw new IllegalStateException("Pending limit configuration changed while locked");
        }

        return new ConfigApplied(activated.limitConfigId(), activated.version(), cutover,
                moneyText(active.map(LimitConfig::perTransactionLimit).orElse(null)),
                moneyText(active.map(LimitConfig::dailyLimit).orElse(null)),
                moneyText(activated.perTransactionLimit()), moneyText(activated.dailyLimit()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RejectedLimitChange rejectPendingChange(long approvalId, long checkerUserId, String correlationId) {
        requirePositive(approvalId, "approvalId");
        requirePositive(checkerUserId, "checkerUserId");
        requireCorrelationId(correlationId);

        LimitConfig initial = store.findByApprovalId(approvalId)
                .orElseThrow(LimitConfigMutationService::invalidPendingState);
        store.lockCustomerScope(initial.customerId());
        LimitConfig pending = store.lockByApprovalId(approvalId)
                .orElseThrow(LimitConfigMutationService::invalidPendingState);
        requirePendingState(pending);
        // The rejecting staff member and decision time live in the Admin approval row; V9 has no
        // rejected_by column (the DB check requires approved_by/approved_at to stay NULL when REJECTED).
        pending.reject();
        if (!store.reject(pending.limitConfigId(), store.currentTime())) {
            throw new IllegalStateException("Pending limit configuration changed while locked");
        }
        return new RejectedLimitChange(approvalId, pending.limitConfigId());
    }

    private void requireCustomerExists(long customerId) {
        if (customers.findByCustomerId(customerId).isEmpty()) {
            throw new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
    }

    private void requireAccountOwnership(Long accountId, long customerId, String correlationId) {
        if (accountId != null) {
            accounts.assertOwnership(accountId, customerId, correlationId);
        }
    }

    private static void requireExpectedActiveVersion(long expectedVersion, Optional<LimitConfig> active) {
        if (expectedVersion == 0) {
            if (active.isPresent()) {
                throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
            }
            return;
        }
        if (active.isEmpty() || !active.get().matchesActiveVersion(expectedVersion)) {
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
    }

    private static void requirePendingState(LimitConfig config) {
        if (config.status() != LimitConfigStatus.PENDING_APPROVAL) {
            throw invalidPendingState();
        }
    }

    private static BankingBusinessException invalidPendingState() {
        return new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, name + " must be positive");
        }
    }

    private static void requireCorrelationId(String correlationId) {
        if (correlationId == null || !CORRELATION_ID.matcher(correlationId).matches()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** The reservation command has no correlation id; the approval id is a stable, safe substitute. */
    private static String approvalCorrelation(long approvalId) {
        return "limit-approval-" + approvalId;
    }

    private static String moneyText(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
