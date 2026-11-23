package com.simulatedbank.banking.limit.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;

/**
 * Persisted lifecycle of a {@code banking.limit_config} row (04 §4.5.1 CHECK
 * {@code ck_limit_status}).
 *
 * <p>The only transition that produces a new effective configuration is
 * {@code PENDING_APPROVAL -> ACTIVE}; the previous {@code ACTIVE} row becomes
 * {@code SUPERSEDED} in the same transaction. A pending row that is denied becomes
 * {@code REJECTED} and can never be approved afterwards. Terminal states never move,
 * which is what makes a second apply of the same approval a business error rather than
 * a silent no-op.</p>
 */
public enum LimitConfigStatus {
    PENDING_APPROVAL,
    ACTIVE,
    SUPERSEDED,
    REJECTED;

    /**
     * Enforces the lifecycle before any mutation. Callers must already hold the row lock
     * ({@code SELECT ... FOR UPDATE}); this method only decides whether the transition is legal.
     *
     * @throws BankingBusinessException {@code INVALID_STATE_TRANSITION} (409) for every illegal move
     */
    public void requireTransitionTo(LimitConfigStatus target) {
        boolean allowed = switch (this) {
            case PENDING_APPROVAL -> target == ACTIVE || target == REJECTED;
            case ACTIVE -> target == SUPERSEDED;
            case SUPERSEDED, REJECTED -> false;
        };
        if (!allowed) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
