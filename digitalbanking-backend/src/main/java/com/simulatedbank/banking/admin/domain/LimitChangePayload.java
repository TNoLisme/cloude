package com.simulatedbank.banking.admin.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Requested limit change (API-LIM-02, 07 §8.5.2).
 *
 * <p>Scope mapping: {@code accountId == null} targets the customer ({@code CUSTOMER}); otherwise the account
 * ({@code ACCOUNT}). The customer identifier is kept in the payload for ownership validation by the Limit
 * provider.</p>
 *
 * <p>{@code expectedVersion} is the Limit configuration version captured from
 * {@code limit.api.LimitConfigMutationPort.currentVersion} at proposal time and persisted (masked from the
 * public payload like the role version). It is bound into the Maker step-up hash and validated again under the
 * pending-row lock by {@code applyApprovedLimitChange}, so a change committed between step-up and apply is a
 * conflict instead of a lost update (BE-ADM-INT-01). Client input always uses version {@code 0}.</p>
 */
public record LimitChangePayload(
        long customerId,
        Long accountId,
        BigDecimal perTransactionLimit,
        BigDecimal dailyLimit,
        String currency,
        Instant effectiveFrom,
        String reason,
        long expectedVersion
) implements ChangePayload {

    private static final String SUPPORTED_CURRENCY = "VND";

    public LimitChangePayload {
        AdminText.positiveId(customerId);
        if (accountId != null) {
            AdminText.positiveId(accountId);
        }
        perTransactionLimit = AdminText.money(perTransactionLimit);
        dailyLimit = AdminText.money(dailyLimit);
        if (perTransactionLimit.compareTo(dailyLimit) > 0) {
            throw AdminText.invalid();
        }
        currency = currency == null ? SUPPORTED_CURRENCY : currency;
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw AdminText.invalid();
        }
        reason = AdminText.optional(reason, 500);
        if (expectedVersion < 0) {
            throw AdminText.invalid();
        }
    }

    /** Client-side request shape: the provider version is captured server-side afterwards. */
    public LimitChangePayload(long customerId, Long accountId, BigDecimal perTransactionLimit, BigDecimal dailyLimit,
            String currency, Instant effectiveFrom, String reason) {
        this(customerId, accountId, perTransactionLimit, dailyLimit, currency, effectiveFrom, reason, 0L);
    }

    @Override
    public ApprovalRequestType requestType() {
        return ApprovalRequestType.LIMIT_CHANGE;
    }

    @Override
    public ApprovalTargetType targetType() {
        return accountId == null ? ApprovalTargetType.CUSTOMER : ApprovalTargetType.ACCOUNT;
    }

    @Override
    public String targetId() {
        return Long.toString(accountId == null ? customerId : accountId);
    }
}
