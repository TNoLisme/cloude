package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.application.port.LimitStore;
import com.simulatedbank.banking.limit.api.LimitChanges;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitConfiguration.Status;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.orm.jpa.EntityManagerHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BankingBusinessException.class)
public class LimitChangeService implements LimitChanges {
    private final LimitStore repository;
    private final LimitChangePolicy policy;
    private final ZoneId businessZoneId;
    private final EntityManagerFactory entityManagerFactory;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public void validateChange(java.math.BigDecimal perTransaction, java.math.BigDecimal daily, String currency, Instant effectiveFrom) {
        if (!"VND".equals(currency)) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        validateAmounts(perTransaction, daily);
        policy.check(perTransaction, daily);
        requireNonFuture(effectiveFrom, repository.currentTime());
    }

    @Override
    public LimitConfiguration prepare(Proposal proposal) {
        requireCallerTransaction();
        if (proposal == null || proposal.customerId() <= 0 || proposal.makerId() <= 0
                || proposal.approvalId() <= 0 || (proposal.accountId() != null && proposal.accountId() <= 0)
                || (proposal.currency() != null && !"VND".equals(proposal.currency()))) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        validateAmounts(proposal.perTransactionLimit(), proposal.dailyLimit());
        policy.check(proposal.perTransactionLimit(), proposal.dailyLimit());
        repository.lockChangeScope(proposal.customerId(), proposal.accountId());
        Instant now = repository.currentTime();
        requireNonFuture(proposal.effectiveFrom(), now);
        var scope = repository.findScope(proposal.customerId(), proposal.accountId());
        requireConsistentScope(scope, proposal.customerId());
        int version = scope.stream().mapToInt(LimitConfiguration::version).max().orElse(0) + 1;
        var config = new LimitConfiguration(null, proposal.customerId(), proposal.accountId(),
                proposal.perTransactionLimit(), proposal.dailyLimit(), "VND",
                proposal.effectiveFrom() == null ? now : proposal.effectiveFrom(), null, Status.PENDING_APPROVAL,
                version, proposal.makerId(), null, proposal.approvalId(), now, null, now);
        return repository.insertProposal(config)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    @Override
    public LimitConfiguration approve(long approvalId, long checkerId) {
        return decide(approvalId, checkerId, true, false, null);
    }

    @Override
    public LimitConfiguration reject(long approvalId, long checkerId) {
        return decide(approvalId, checkerId, false, false, null);
    }

    @Override
    public LimitConfiguration approve(long approvalId, long checkerId, Integer expectedCurrentVersion) {
        return decide(approvalId, checkerId, true, true, expectedCurrentVersion);
    }

    private LimitConfiguration decide(long approvalId, long checkerId, boolean approve,
            boolean checkVersion, Integer expectedCurrentVersion) {
        requireCallerTransaction();
        if (approvalId <= 0 || checkerId <= 0) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        var initial = repository.findProposal(approvalId, false)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        repository.lockChangeScope(initial.customerId(), initial.accountId());
        var pending = repository.findProposal(approvalId, true).orElseThrow();
        if (pending.status() != Status.PENDING_APPROVAL) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        if (pending.createdBy() == checkerId) throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        Instant now = repository.currentTime();
        var scope = repository.findScope(pending.customerId(), pending.accountId());
        requireConsistentScope(scope, pending.customerId());
        if (approve) {
            validateAmounts(pending.perTransactionLimit(), pending.dailyLimit());
            policy.check(pending.perTransactionLimit(), pending.dailyLimit());
            requireNonFuture(pending.effectiveFrom(), now);
            var active = scope.stream().filter(config -> config.status() == Status.ACTIVE).findFirst().orElse(null);
            boolean effectiveAccount = active != null && !active.effectiveFrom().isAfter(now)
                    && (active.effectiveTo() == null || active.effectiveTo().isAfter(now));
            if (checkVersion && !java.util.Objects.equals(expectedCurrentVersion, effectiveAccount ? active.version() : null)) {
                throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            if (pending.accountId() != null && !effectiveAccount) {
                var usage = repository.findUsage(pending.customerId(), null, now.atZone(businessZoneId).toLocalDate());
                if (usage.isPresent() && (usage.get().usedAmount().signum() != 0 || usage.get().transferCount() != 0)) {
                    throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
                }
            }
            if (active != null) {
                if (!active.effectiveFrom().isBefore(now)) {
                    throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
                }
                repository.supersede(active.limitId(), now);
            }
        }
        return repository.decideProposal(pending.limitId(), approve, checkerId, now);
    }

    private void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().getTransaction().isActive()) {
            throw new IllegalTransactionStateException("Limit changes require a writable caller transaction");
        }
        repository.transactionStart(); // Also enforces READ COMMITTED before acquiring any lock or writing.
    }

    private static void validateAmounts(java.math.BigDecimal perTransaction, java.math.BigDecimal daily) {
        try {
            LimitAmounts.requireValid(perTransaction, false);
            LimitAmounts.requireValid(daily, false);
            if (perTransaction.compareTo(daily) > 0) throw new IllegalArgumentException("Invalid limit relationship");
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireNonFuture(Instant effectiveFrom, Instant now) {
        if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireConsistentScope(java.util.List<LimitConfiguration> scope, long customerId) {
        if (scope.stream().anyMatch(config -> config.customerId() != customerId)) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
