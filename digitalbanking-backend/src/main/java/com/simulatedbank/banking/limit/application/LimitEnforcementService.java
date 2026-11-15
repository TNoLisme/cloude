package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJdbcRepository;
import com.simulatedbank.banking.limit.api.ApplicableLimits;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitEnforcement;
import com.simulatedbank.banking.limit.api.LimitRules.CheckOutcome;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LimitEnforcementService implements LimitEnforcement {
    private final LimitJdbcRepository repository;
    private final ZoneId businessZoneId;
    private final EntityManagerFactory entityManagerFactory;

    public LimitEnforcementService(LimitJdbcRepository repository, ZoneId businessZoneId,
            EntityManagerFactory entityManagerFactory) {
        this.repository = repository;
        this.businessZoneId = businessZoneId;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BankingBusinessException.class)
    public Check lockAndCheck(long customerId, long sourceAccountId, BigDecimal amount) {
        requireWritableTransaction();
        try {
            LimitAmounts.requireValid(amount, false);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Instant asOf = repository.transactionStart();
        LocalDate date = asOf.atZone(businessZoneId).toLocalDate();
        var customer = repository.lockUsage(customerId, null, date);
        var limits = new ApplicableLimits(customerId, sourceAccountId, asOf,
                repository.findEffective(customerId, sourceAccountId, asOf));
        var accountConfig = limits.items().stream().filter(config -> config.accountId() != null)
                .findFirst().orElse(null);
        var account = accountConfig == null ? null : repository.lockUsage(customerId, sourceAccountId, date);
        for (var config : limits.items()) {
            var usage = config.accountId() == null ? customer : account;
            if (amount.compareTo(config.perTransactionLimit()) > 0
                    || usage.usedAmount().add(amount).compareTo(config.dailyLimit()) > 0) {
                throw new BankingBusinessException(ErrorCode.LIMIT_EXCEEDED);
            }
        }
        var checked = new Checked(amount, limits, customer, account);
        TransactionSynchronizationManager.registerSynchronization(checked);
        return checked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompleted(Check check) {
        requireWritableTransaction();
        if (!(check instanceof Checked checked) || checked.owner != this || checked.recorded || checked.expired
                || checked.outcome() != CheckOutcome.PASS
                || !TransactionSynchronizationManager.getSynchronizations().contains(checked)) {
            throw new IllegalArgumentException("Completion requires an unused check from this caller transaction");
        }
        repository.incrementUsage(checked.customer, checked.amount, checked.dailyLimit(null));
        if (checked.account != null) {
            repository.incrementUsage(checked.account, checked.amount, checked.dailyLimit(checked.account.accountId()));
        }
        checked.recorded = true;
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().getTransaction().isActive()) {
            throw new IllegalTransactionStateException("Limit enforcement requires a writable caller transaction");
        }
    }

    private final class Checked implements Check, TransactionSynchronization {
        private final LimitEnforcementService owner = LimitEnforcementService.this;
        private final BigDecimal amount;
        private final ApplicableLimits limits;
        private final LimitUsage customer;
        private final LimitUsage account;
        private boolean recorded;
        private boolean expired;

        private Checked(BigDecimal amount, ApplicableLimits limits, LimitUsage customer, LimitUsage account) {
            this.amount = amount;
            this.limits = limits;
            this.customer = customer;
            this.account = account;
        }

        private BigDecimal dailyLimit(Long accountId) {
            return limits.items().stream().filter(config -> java.util.Objects.equals(config.accountId(), accountId))
                    .map(LimitConfiguration::dailyLimit).findFirst().orElse(null);
        }

        @Override public CheckOutcome outcome() { return limits.items().isEmpty() ? CheckOutcome.NOT_CONFIGURED : CheckOutcome.PASS; }
        @Override public Instant asOf() { return limits.asOf(); }
        @Override public LocalDate usageDate() { return customer.usageDate(); }
        @Override public LimitUsage customerUsage() { return customer; }
        @Override public LimitUsage accountUsage() { return account; }
        // Spring still exposes transaction resources during afterCommit; expire before the DB completion.
        @Override public void beforeCompletion() { expired = true; }
    }
}
