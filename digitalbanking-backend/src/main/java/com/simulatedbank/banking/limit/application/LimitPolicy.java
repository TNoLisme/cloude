package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Applies the configured Limit policy bounds (OI-API-05).
 *
 * <p>07 §8.5.2 requires new limits to be "trong policy" but leaves the actual numeric
 * bounds open (OI-API-05). The provider exposes them as optional configuration
 * {@code app.limit.policy.max-per-transaction} / {@code app.limit.policy.max-daily} and
 * rejects a request above a configured bound with {@code LIMIT_OUT_OF_POLICY} (422).
 * No default is invented: an unset bound means the provider does not impose that bound.
 * Policy is checked on proposal and re-checked on approval, so a bound tightened while an
 * approval is pending still prevents the stale change from becoming effective.</p>
 */
@Component
@ConditionalOnProperty(name = {"app.limit.enabled"}, havingValue = "true", matchIfMissing = true)
public class LimitPolicy {

    private final BigDecimal maxPerTransaction;
    private final BigDecimal maxDaily;

    public LimitPolicy(LimitPolicyProperties properties) {
        this.maxPerTransaction = properties.maxPerTransaction();
        this.maxDaily = properties.maxDaily();
    }

    /**
     * @throws BankingBusinessException {@code LIMIT_OUT_OF_POLICY} when a configured bound is exceeded
     */
    public void requireWithinPolicy(BigDecimal perTransactionLimit, BigDecimal dailyLimit) {
        if (maxPerTransaction != null && perTransactionLimit.compareTo(maxPerTransaction) > 0) {
            throw new BankingBusinessException(ErrorCode.LIMIT_OUT_OF_POLICY);
        }
        if (maxDaily != null && dailyLimit.compareTo(maxDaily) > 0) {
            throw new BankingBusinessException(ErrorCode.LIMIT_OUT_OF_POLICY);
        }
    }
}
