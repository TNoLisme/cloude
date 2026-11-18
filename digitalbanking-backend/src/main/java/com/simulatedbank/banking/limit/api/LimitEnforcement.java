package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** T2 provider: caller owns transfer/account locks, money work, terminal state and audit. */
public interface LimitEnforcement {
    Check lockAndCheck(long customerId, long sourceAccountId, BigDecimal amount);

    /** After successful money work, before committing the same caller transaction. */
    void recordCompleted(Check check);

    /** Opaque, single-use authorization; invalid after its caller transaction ends. */
    interface Check {
        LimitRules.CheckOutcome outcome();
        Instant asOf();
        LocalDate usageDate();
        LimitUsage customerUsage();
        LimitUsage accountUsage();
    }
}
