package com.simulatedbank.banking.admin.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Objects;

/**
 * Status values of the canonical {@code banking.approval_request} schema (04 §4.5.3).
 *
 * <p>Central transition matrix of the MVP workflow (CON-01 §6):</p>
 * <pre>
 * PENDING -> APPLIED   (approve + provider apply + C2 audit)
 * PENDING -> REJECTED  (reject, no provider mutation)
 * PENDING -> CANCELLED (not exposed by an API in the MVP)
 * </pre>
 *
 * <p>Every decided status is terminal. {@link #APPROVED} is schema-reserved for a future asynchronous apply
 * step and is deliberately not reachable in the MVP. The aggregate validates transitions through this enum so
 * no controller or adapter has to duplicate the rule.</p>
 */
public enum ApprovalStatus {
    PENDING,
    /**
     * Reserved by the schema for a future asynchronous apply step. The MVP applies the provider change in the
     * checker transaction, so a persisted request goes {@code PENDING -> APPLIED} or {@code PENDING -> REJECTED}.
     */
    APPROVED,
    REJECTED,
    APPLIED,
    CANCELLED;

    public boolean isPending() {
        return this == PENDING;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }

    /** Whether the approved MVP state machine allows {@code this -> target}. */
    public boolean canTransitionTo(ApprovalStatus target) {
        Objects.requireNonNull(target, "target status is required");
        return switch (this) {
            case PENDING -> target == APPLIED || target == REJECTED || target == CANCELLED;
            case APPROVED, REJECTED, APPLIED, CANCELLED -> false;
        };
    }

    /**
     * Enforces the transition matrix. A terminal request cannot change again; the catalog code stays
     * {@code APPROVAL_ALREADY_DECIDED} for a duplicate decision (07 §4). A non-terminal but unsupported
     * target (for example the schema-reserved {@code APPROVED}) is {@code INVALID_STATE_TRANSITION}.
     */
    public void requireTransitionTo(ApprovalStatus target) {
        if (!canTransitionTo(target)) {
            ErrorCode code = isTerminal()
                    ? ErrorCode.APPROVAL_ALREADY_DECIDED
                    : ErrorCode.INVALID_STATE_TRANSITION;
            throw new BankingBusinessException(code);
        }
    }
}
