package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJdbcRepository;
import com.simulatedbank.banking.limit.api.ApplicableLimits;
import com.simulatedbank.banking.limit.api.LimitRules;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class LimitRuleService implements LimitRules {
    private final LimitJdbcRepository repository;

    public LimitRuleService(LimitJdbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApplicableLimits resolve(long customerId, long sourceAccountId, Instant asOf) {
        Objects.requireNonNull(asOf, "Evaluation instant is required");
        return new ApplicableLimits(customerId, sourceAccountId, asOf,
                repository.findEffective(customerId, sourceAccountId, asOf));
    }

    @Override
    public CheckOutcome checkPerTransaction(long customerId, long sourceAccountId, BigDecimal amount, Instant asOf) {
        try {
            LimitAmounts.requireValid(amount, false);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var applicable = resolve(customerId, sourceAccountId, asOf);
        if (applicable.items().isEmpty()) {
            return CheckOutcome.NOT_CONFIGURED;
        }
        for (var config : applicable.items()) {
            if (amount.compareTo(config.perTransactionLimit()) > 0) {
                throw new BankingBusinessException(ErrorCode.LIMIT_EXCEEDED);
            }
        }
        return CheckOutcome.PASS;
    }
}
