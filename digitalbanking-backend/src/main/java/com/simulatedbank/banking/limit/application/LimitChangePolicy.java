package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import java.math.BigDecimal;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit change policy; existing transfer defaults are not configuration policy. */
@Component
public class LimitChangePolicy {
    private final Environment environment;

    public LimitChangePolicy(Environment environment) {
        this.environment = environment;
    }

    public void check(BigDecimal perTransaction, BigDecimal daily) {
        BigDecimal maxPer;
        BigDecimal maxDaily;
        try {
            maxPer = new BigDecimal(environment.getProperty("app.limit.policy.max-per-transaction-vnd", ""));
            maxDaily = new BigDecimal(environment.getProperty("app.limit.policy.max-daily-vnd", ""));
            LimitAmounts.requireValid(maxPer, false);
            LimitAmounts.requireValid(maxDaily, false);
            if (maxPer.compareTo(maxDaily) > 0) throw new IllegalArgumentException("Invalid policy range");
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (perTransaction.compareTo(maxPer) > 0 || daily.compareTo(maxDaily) > 0) {
            throw new BankingBusinessException(ErrorCode.LIMIT_OUT_OF_POLICY);
        }
    }
}
