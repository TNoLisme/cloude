package com.simulatedbank.banking.limit.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.limit.application.port.LimitStore;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LimitJpaAdapter implements LimitStore {
    private final LimitConfigJpaRepository configs;
    private final LimitUsageJpaRepository usages;
    private final LimitPersistenceMapper mapper;
    private final EntityManager entityManager;
    private final LimitCustomerGuard guard;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public LimitConfiguration insertConfiguration(LimitConfiguration config) {
        validate(config);
        var entity = configs.save(mapper.toEntity(config));
        entityManager.refresh(entity);
        return snapshot(entity);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LimitConfiguration> insertProposal(LimitConfiguration config) {
        validate(config);
        int count = configs.insertProposal(config.customerId(), config.accountId(), config.perTransactionLimit(),
                config.dailyLimit(), config.currency(), config.effectiveFrom(), config.effectiveTo(),
                config.status() == null ? null : config.status().name(), config.version(), config.createdBy(),
                config.approvedBy(), config.approvalId(), config.createdAt(), config.approvedAt(), config.updatedAt());
        return count == 0 ? Optional.empty() : configs.proposal(config.approvalId()).map(this::snapshot);
    }

    private static void validate(LimitConfiguration config) {
        if (config.limitId() != null) throw new IllegalArgumentException("New configuration must not have an ID");
        if (config.currency() == null || config.status() == null)
            throw new DataIntegrityViolationException("Configuration currency and status are required");
        LimitAmounts.requireValid(config.perTransactionLimit(), false);
        LimitAmounts.requireValid(config.dailyLimit(), false);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public LimitUsage insertUsage(LimitUsage usage) {
        if (usage.usageId() != null) throw new IllegalArgumentException("New usage must not have an ID");
        if (usage.currency() == null) throw new DataIntegrityViolationException("Usage currency is required");
        LimitAmounts.requireValid(usage.usedAmount(), true);
        var entity = usages.save(mapper.toEntity(usage));
        entityManager.refresh(entity);
        return snapshot(entity);
    }
    @Override public Optional<LimitConfiguration> findConfiguration(long id) { return configs.findById(id).map(this::snapshot); }
    @Override public Optional<LimitUsage> findUsage(long customer, Long account, LocalDate date) {
        return usages.usage(customer, account, date).map(this::snapshot);
    }
    @Override public List<LimitConfiguration> findEffective(long customer, long account, Instant asOf) {
        return configs.effective(customer, account, asOf.truncatedTo(ChronoUnit.MICROS)).stream().map(this::snapshot).toList();
    }
    @Override public List<LimitConfiguration> findTransferConfigurations(long customer, long account, Instant asOf) {
        return configs.transferConfigurations(customer, account, asOf).stream().map(this::snapshot).toList();
    }
    @Override public boolean hasActivationAfter(long customer, long account, Instant asOf) {
        return configs.hasActivationAfter(customer, account, asOf.truncatedTo(ChronoUnit.MICROS));
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Instant transactionStart() {
        if (!"read committed".equals(configs.isolation())) throw new IllegalArgumentException("Limit enforcement requires READ COMMITTED isolation");
        return configs.transactionTimestamp();
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public LimitUsage lockUsage(long customer, Long account, LocalDate date) {
        if (account == null) usages.createCustomerUsage(customer, date);
        else usages.createAccountUsage(customer, account, date);
        var rows = usages.lockUsage(customer, account, date);
        if (rows.size() != 1) throw new IllegalStateException("Usage scope does not match caller customer/account");
        return snapshot(rows.getFirst());
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void incrementUsage(LimitUsage usage, BigDecimal amount, BigDecimal dailyLimit) {
        int count = usages.increment(usage.usageId(), usage.customerId(), usage.accountId(), usage.usageDate(), amount, dailyLimit);
        if (count != 1) throw new IllegalStateException("Checked usage no longer permits completion");
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lockCustomerScope(long customer) {
        guard.lock(customer);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockCustomerScope(long customer) {
        return guard.tryLock(customer);
    }
    @Override public Instant currentTime() { return configs.clockTimestamp(); }
    @Override public List<LimitConfiguration> findScope(long customer, Long account) {
        return configs.scope(customer, account).stream().map(this::snapshot).toList();
    }
    @Override public Optional<LimitConfiguration> findProposal(long approval, boolean forUpdate) {
        return (forUpdate ? configs.lockProposal(approval) : configs.proposal(approval)).map(this::snapshot);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void supersede(long id, Instant now) {
        if (configs.supersede(id, now) != 1) throw new IllegalStateException("Active configuration changed during decision");
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public LimitConfiguration decideProposal(long id, boolean approve, long checker, Instant now) {
        if (configs.decide(id, approve ? "ACTIVE" : "REJECTED", approve, approve ? checker : null,
                approve ? now : null, now) != 1) throw new IllegalStateException("Pending configuration changed during decision");
        return configs.findById(id).map(this::snapshot).orElseThrow();
    }

    // Native writes must never leave a stale managed entity that can overwrite a later update.
    private LimitConfiguration snapshot(LimitConfigEntity entity) {
        var snapshot = mapper.toSnapshot(entity);
        entityManager.detach(entity);
        return snapshot;
    }
    private LimitUsage snapshot(LimitUsageEntity entity) {
        var snapshot = mapper.toSnapshot(entity);
        entityManager.detach(entity);
        return snapshot;
    }
}
