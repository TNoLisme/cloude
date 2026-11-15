package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Per-transaction provider only; callers must enforce daily usage separately in T2. */
public interface LimitRules {
    ApplicableLimits resolve(long customerId, long sourceAccountId, Instant asOf);

    CheckOutcome checkPerTransaction(long customerId, long sourceAccountId, BigDecimal amount, Instant asOf);

    /** NOT_CONFIGURED is not permission to transfer; D1 remains a caller policy decision. */
    enum CheckOutcome { PASS, NOT_CONFIGURED }
}
