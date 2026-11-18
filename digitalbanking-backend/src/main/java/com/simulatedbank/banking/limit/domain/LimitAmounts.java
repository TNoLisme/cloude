package com.simulatedbank.banking.limit.domain;

import java.math.BigDecimal;

/** Check before JDBC: PostgreSQL NUMERIC(19,2) otherwise rounds fractional digits. */
public final class LimitAmounts {
    private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    private LimitAmounts() {
    }

    public static void requireValid(BigDecimal amount, boolean allowZero) {
        if (amount == null || amount.scale() > 2 || amount.signum() < 0
                || (!allowZero && amount.signum() == 0) || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Invalid limit amount");
        }
    }
}
