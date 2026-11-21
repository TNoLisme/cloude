package com.simulatedbank.banking.limit.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.application.port.LimitStore;
import com.simulatedbank.banking.limit.api.ApplicableLimits;
import com.simulatedbank.banking.limit.api.LimitRules;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class LimitRuleService implements LimitRules {
    private final LimitStore repository;

    @Override
    public ApplicableLimits resolve(long customerId, long sourceAccountId, Instant asOf) {
        Objects.requireNonNull(asOf, "Evaluation instant is required");
        return new ApplicableLimits(customerId, sourceAccountId, asOf,
                repository.findEffective(customerId, sourceAccountId, asOf));
    }

    @Override
    public java.util.Optional<com.simulatedbank.banking.limit.api.LimitConfiguration> current(long customerId, Long accountId, Instant asOf) {
        return repository.findScope(customerId, accountId).stream()
                .filter(config -> config.customerId() == customerId
                        && (config.status() == com.simulatedbank.banking.limit.api.LimitConfiguration.Status.ACTIVE
                            || (config.status() == com.simulatedbank.banking.limit.api.LimitConfiguration.Status.SUPERSEDED
                                && config.effectiveTo() != null))
                        && !config.effectiveFrom().isAfter(asOf)
                        && (config.effectiveTo() == null || config.effectiveTo().isAfter(asOf)))
                .findFirst();
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
