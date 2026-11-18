package com.simulatedbank.banking.ledger.domain;

import java.math.BigDecimal;

/** Money value object: validates representation before normalizing to scale 2 (BE-LED-03 §5.2). */
public final class Money {

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    public static Money amount(BigDecimal raw) {
        return create(raw, 1, "amount");
    }

    public static Money balance(BigDecimal raw) {
        return create(raw, 0, "balanceAfter");
    }

    /** Representation rule shared with the read-back pair check: sign, scale <= 2, <= 17 integer digits. */
    public static boolean satisfies(BigDecimal raw, int minimumSign) {
        return raw != null && raw.signum() >= minimumSign && raw.scale() <= 2
                && (long) raw.precision() - raw.scale() <= 17;
    }

    private static Money create(BigDecimal raw, int minimumSign, String field) {
        if (raw == null) {
            throw new InvalidPostingCommandException(field + " must not be null");
        }
        if (raw.signum() < minimumSign) {
            throw new InvalidPostingCommandException(field + (minimumSign > 0 ? " must be positive" : " must not be negative"));
        }
        // Reject excess scale before normalization so 1.000 / 1.235 never become valid by rounding.
        if (raw.scale() > 2) {
            throw new InvalidPostingCommandException(field + " must have scale <= 2");
        }
        if ((long) raw.precision() - raw.scale() > 17) {
            throw new InvalidPostingCommandException(field + " must have at most 17 integer digits");
        }
        return new Money(raw.setScale(2));
    }

    public BigDecimal value() {
        return value;
    }
}