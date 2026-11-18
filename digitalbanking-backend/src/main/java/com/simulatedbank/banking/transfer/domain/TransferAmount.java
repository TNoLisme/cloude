package com.simulatedbank.banking.transfer.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact positive VND amount representable by NUMERIC(19,2). */
public record TransferAmount(BigDecimal value, String currency) {
    private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    public TransferAmount {
        Objects.requireNonNull(value, "value");
        if (!"VND".equals(currency)) {
            throw new IllegalArgumentException("Currency must be VND");
        }
        if (value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Amount must be positive and fit NUMERIC(19,2) without rounding");
        }
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
