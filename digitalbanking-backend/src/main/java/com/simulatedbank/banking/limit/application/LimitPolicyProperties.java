package com.simulatedbank.banking.limit.application;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional Limit policy bounds ({@code app.limit.policy.*}).
 *
 * <p>The concrete maxima are <b>unresolved upstream</b> (OI-API-05 in 07 §8.5.2). This
 * provider therefore invents no default: when a bound is absent it is not enforced; when
 * configured, a requested value above it is rejected with {@code LIMIT_OUT_OF_POLICY}
 * (422). Bounds are validated at startup so a malformed configuration cannot silently
 * weaken the policy.</p>
 *
 * @param maxPerTransaction upper bound for one transaction, or {@code null} = not configured
 * @param maxDaily           upper bound for the daily limit, or {@code null} = not configured
 */
@ConfigurationProperties(prefix = "app.limit.policy")
public record LimitPolicyProperties(BigDecimal maxPerTransaction, BigDecimal maxDaily) {

    public LimitPolicyProperties {
        maxPerTransaction = requirePositive(maxPerTransaction, "app.limit.policy.max-per-transaction");
        maxDaily = requirePositive(maxDaily, "app.limit.policy.max-daily");
        if (maxPerTransaction != null && maxDaily != null && maxDaily.compareTo(maxPerTransaction) < 0) {
            throw new IllegalArgumentException("app.limit.policy.max-daily must be >= max-per-transaction");
        }
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive when configured");
        }
        return value;
    }
}
