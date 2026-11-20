package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountTransferEvidence;
import com.simulatedbank.banking.audit.api.TransferAuditEvidence;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferRecoveryStore;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore.Details;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention.Snapshot;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.application.TransferNotifications;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.ReplayResponse;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate;
import com.simulatedbank.banking.transfer.application.TransferRecoveryPersistence.State;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AuditWriteException;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.CreditFailure;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import com.simulatedbank.banking.transfer.application.TransferView;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims a committed T1 once and executes the fenced settlement on the primary. */
public final class TransferSettlementHandoff implements TransferCreateAdapters.PostCommitSettlement {
    private static final Logger LOG = LoggerFactory.getLogger(TransferSettlementHandoff.class);
    private static final Set<String> RETRY_STATES = Set.of("40P01", "40001", "55P03");
    private static final int MAX_ATTEMPTS = 2;
    private static final int CLAIM_LEASE_SECONDS = 120;
    private final PlatformTransactionManager transactions;
    final ZoneId zone;
    private final TransferRecoveryCandidate recovery;
    private final TransferRecoveryAdapter recoveryPorts;
    private final TransferTelemetry telemetry;
    private final TransactionTemplate claimTx;
    private final int timeoutSeconds;
    final JdbcTemplate jdbc;
    final TransferSettlementStore store;
    final TransferRecoveryStore claims;
    final AccountBalance accounts;
    final AccountTransferEvidence movements;
    final AccountQuery accountQuery;
    final LedgerPosting ledger;
    final TransferLimits limits;
    final TransferLimitAdapter limitAdapter;
    final TransferAuditAdapter audit;
    final TransferNotifications notifications;
    final TransferFraudAdapter fraud;
    final ResponseEncoder responses;
    final Supplier<Snapshot> ttl;
    final Runnable flush;
    final long lockMs;
    final long statementMs;

    public TransferSettlementHandoff(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ZoneId zone, TransferTelemetry telemetry, AccountBalance accounts, AccountQuery accountQuery,
            LedgerPosting ledger, TransferLimits limits, TransferLimitAdapter limitAdapter,
            TransferAuditAdapter audit, TransferNotifications notifications, TransferFraudAdapter fraud, ResponseEncoder responses,
            Supplier<Snapshot> ttl, Runnable flush, TransferSettlementStore store, long lockMs, long statementMs,
            AccountTransferEvidence movements, TransferAuditEvidence auditReads, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.zone = zone;
        this.telemetry = telemetry;
        this.accounts = accounts;
        this.movements = movements;
        this.accountQuery = accountQuery;
        this.ledger = ledger;
        this.limits = limits;
        this.limitAdapter = limitAdapter;
        this.audit = audit;
        this.notifications = notifications;
        this.fraud = fraud;
        this.responses = responses;
        this.ttl = ttl;
        this.flush = flush;
        if (lockMs < 1 || statementMs < 1 || statementMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid T2 timeout configuration");
        }
        this.lockMs = lockMs;
        this.statementMs = statementMs;
        this.store = store;
        this.claims = new TransferRecoveryStore(jdbc);
        this.timeoutSeconds = Math.toIntExact((statementMs + 999) / 1000);
        claimTx = new TransactionTemplate(transactions);
        claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        claimTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        claimTx.setTimeout(timeoutSeconds);
        recoveryPorts = new TransferRecoveryAdapter(this, auditReads, mapper);
        recovery = newRecoveryWorker();
    }

    public TransferRecoveryCandidate newRecoveryWorker() {
        return new TransferRecoveryCandidate(transactions, claims, this::settleInRecoveryTransaction,
                recoveryPorts, zone, UUID.randomUUID().toString(), telemetry);
    }

    @Override
    public Settlement settle(long transferId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer claim requires the committed T1");
        }
        Details details = store.read(transferId);
        AttemptFence fence;
        try {
            fence = claimTx.execute(status -> claim(details));
        } catch (BankingBusinessException busy) {
            throw busy;
        } catch (RuntimeException uncertain) {
            LOG.warn("TRANSFER_CLAIM_ERROR correlationId={}", details.intent().correlationId());
            throw unavailable(uncertain);
        }
        for (int attempt = 1; ; attempt++) {
            var ports = new TransferSettlementAdapter(this, details);
            try {
                Outcome outcome = executor(ports).settle(transferId, fence);
                if (!ports.committed()) throw new IllegalStateException("T2 commit was not confirmed");
                return new Settlement(outcome, ports.response());
            } catch (AuditWriteException failure) {
                if (!ports.rolledBack()) recovery.quarantine(details.context(), fence);
                throw failure;
            } catch (RuntimeException failure) {
                String sqlState = sqlState(failure);
                if (ports.rolledBack() && sqlState != null && RETRY_STATES.contains(sqlState)
                        && attempt < MAX_ATTEMPTS) {
                    pauseBeforeRetry();
                    continue;
                }
                if (ports.rolledBack() && CreditFailure.permitsTfail(failure)) {
                    try {
                        return recovery.failAfterRollback(details.context(), fence)
                                .orElseThrow(() -> unavailable(failure));
                    } catch (AuditWriteException auditFailure) {
                        throw auditFailure;
                    } catch (RuntimeException uncertain) {
                        throw unavailable(uncertain);
                    }
                }
                if (ports.preparedForCommit() || !ports.rolledBack()
                        || sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("57P0"))) {
                    try {
                        if (ports.hasCommittedResponse()) {
                            return new Settlement(ports.preparedOutcome(), ports.response());
                        }
                    } catch (RuntimeException readFailure) {
                        LOG.warn("TRANSFER_T2_COMMIT_READ_ERROR correlationId={}", details.intent().correlationId());
                    }
                    recovery.quarantine(details.context(), fence);
                    LOG.error("TRANSFER_T2_OUTCOME_UNCERTAIN correlationId={}", details.intent().correlationId());
                    throw unavailable(failure);
                }
                LOG.error("TRANSFER_T2_ROLLED_BACK correlationId={} failureType={} sqlState={}",
                        details.intent().correlationId(), failure.getClass().getSimpleName(), sqlState);
                if (failure instanceof BankingBusinessException business
                        && business.code() == ErrorCode.IDEMPOTENCY_IN_PROGRESS) throw business;
                if (failure instanceof TransactionTimedOutException
                        || sqlState != null && (RETRY_STATES.contains(sqlState) || "57014".equals(sqlState))) {
                    throw unavailable(failure);
                }
                throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
    }

    /** Provisional result for the caller's already-controlled recovery transaction. */
    public Outcome settleInRecoveryTransaction(Context context, AttemptFence fence) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recovery settlement requires the caller transaction");
        }
        Details details = store.read(context.transferId());
        if (!details.context().equals(context)) throw new IllegalStateException("Recovery intent context mismatch");
        return executor(new TransferSettlementAdapter(this, details)).settleInRecoveryTransaction(context, fence);
    }

    private TransferSettlementCandidate executor(TransferSettlementAdapter ports) {
        return new TransferSettlementCandidate(transactions, ports, zone, telemetry, timeoutSeconds);
    }

    private AttemptFence claim(Details details) {
        store.setTimeouts(lockMs, statementMs);
        limits.lockStepUpGuard(details.intent().customerId());
        var row = claims.lock(details.context()).orElseThrow(TransferSettlementHandoff::busy);
        if (!row.matches(details.context()) || !details.equals(store.read(details.intent().id()))) {
            throw new IllegalStateException("Transfer intent changed before claim");
        }
        if (!row.pending() || row.state() != State.IDLE || row.epoch() != 0) throw busy();
        Instant now = claims.now();
        return claims.claim(row, "HTTP", now.plusSeconds(CLAIM_LEASE_SECONDS), row.count(), row.first(), row.next());
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(50, 101));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        }
    }

    private static BankingBusinessException busy() {
        return new BankingBusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    private static BankingBusinessException unavailable(Throwable cause) {
        return new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.detail(),
                Map.of(), cause);
    }

    @FunctionalInterface
    public interface ResponseEncoder {
        ReplayResponse encode(Details intent, TransferView success, TransferFailureCode failure, Instant terminalAt);
    }
}
