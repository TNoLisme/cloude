package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountBalance.BalanceAfter;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountTransferEvidence;
import com.simulatedbank.banking.account.api.AccountQuery.AccountSnapshot;
import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.limit.api.TransferLimits.Guard;
import com.simulatedbank.banking.limit.api.TransferLimits.Reservation;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferRecoveryStore;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore.Details;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention.Snapshot;
import com.simulatedbank.banking.transfer.application.MaskedAccount;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Committed;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.application.TransferNotifications;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferRecoveryPersistence.State;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AuditWriteException;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.CreditFailure;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Failure;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Flag;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.FraudResult;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Ports;
import com.simulatedbank.banking.transfer.application.TransferView;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One instance per T2 transaction. Holds only transaction-local snapshots and capabilities. */
final class TransferSettlementAdapter implements Ports, TransactionSynchronization {
    private final Details details;
    private final JdbcTemplate jdbc;
    private final TransferSettlementStore store;
    private final TransferRecoveryStore claims;
    private final AccountBalance accounts;
    private final AccountTransferEvidence movements;
    private final AccountQuery accountQuery;
    private final LedgerPosting ledger;
    private final TransferLimits limits;
    private final TransferLimitAdapter limitAdapter;
    private final TransferAuditAdapter audit;
    private final TransferNotifications notifications;
    private final TransferFraudAdapter fraud;
    private final TransferSettlementHandoff.ResponseEncoder responses;
    private final Supplier<Snapshot> ttl;
    private final Runnable flush;
    private final long lockMs;
    private final long statementMs;
    private long started;
    private int completion = -1;
    private AttemptFence fence;
    private State terminalFenceState = State.CLAIMED;
    private Guard guard;
    private Reservation reservation;
    private TransferLimitAdapter.Check limitCheck;
    private Optional<Failure> accountFailure;
    private BalanceAfter debitBalance;
    private BalanceAfter creditBalance;
    private Instant terminalAt;
    private Committed response;
    private Outcome preparedOutcome;
    private boolean readyToCommit;

    TransferSettlementAdapter(TransferSettlementHandoff handoff, Details details) {
        this.details = details;
        this.jdbc = handoff.jdbc;
        this.store = handoff.store;
        this.claims = handoff.claims;
        this.accounts = handoff.accounts;
        this.movements = handoff.movements;
        this.accountQuery = handoff.accountQuery;
        this.ledger = handoff.ledger;
        this.limits = handoff.limits;
        this.limitAdapter = handoff.limitAdapter;
        this.audit = handoff.audit;
        this.notifications = handoff.notifications;
        this.fraud = handoff.fraud;
        this.responses = handoff.responses;
        this.ttl = handoff.ttl;
        this.flush = handoff.flush;
        this.lockMs = handoff.lockMs;
        this.statementMs = handoff.statementMs;
    }

    @Override
    public Context readContextOnPrimary(long id) {
        if (details.intent().id() != id) throw new IllegalStateException("Transfer context mismatch");
        return details.context();
    }

    @Override
    public void lockStepUpGuard(long customerId) {
        started = System.nanoTime();
        TransactionSynchronizationManager.registerSynchronization(this);
        store.setTimeouts(lockMs, statementMs);
        guard = limits.lockStepUpGuard(customerId);
    }

    @Override
    public AttemptFence lockPendingAndReadFence(Context context) {
        fence = claims.lockUsableFence(context);
        if (!details.equals(store.read(context.transferId()))) {
            throw new IllegalStateException("Transfer intent changed after primary read");
        }
        return fence;
    }

    @Override
    public void lockAccountsAscending(Context context) {
        Intent intent = details.intent();
        if (intent.sourceId() == intent.destinationId() || !"VND".equals(intent.currency())) {
            throw new IllegalStateException("Invalid durable transfer intent");
        }
        accountFailure = accounts.findAndLockTransferAccounts(intent.sourceId(), intent.destinationId())
                .map(pair -> {
                    if (pair.source().accountId() != intent.sourceId()
                            || pair.destination().accountId() != intent.destinationId()
                            || pair.source().customerId() != intent.customerId()
                            || !intent.currency().equals(pair.source().currency())
                            || !intent.currency().equals(pair.destination().currency())) {
                        throw new IllegalStateException("Locked account does not match transfer intent");
                    }
                    if (pair.source().status() != AccountQuery.Status.ACTIVE
                            || pair.destination().status() != AccountQuery.Status.ACTIVE) {
                        return Optional.of(Failure.ACCOUNT_INACTIVE);
                    }
                    if (pair.source().availableBalance().compareTo(intent.amount()) < 0) {
                        return Optional.of(Failure.INSUFFICIENT_BALANCE);
                    }
                    return Optional.<Failure>empty();
                }).orElseGet(() -> Optional.of(Failure.ACCOUNT_NOT_FOUND));
    }

    @Override
    public Instant primaryClockTimestamp() {
        return store.primaryTime();
    }

    @Override
    public void lockUsageCustomerThenAccount(Context context, Instant at, LocalDate date) {
        Intent intent = details.intent();
        var link = new TransferLimits.TransferLink(intent.id(), intent.customerId(), intent.sourceId(),
                intent.amount(), intent.currency(), intent.createdAt());
        reservation = limits.findPendingReservation(guard, link)
                .orElseThrow(() -> new IllegalStateException("Missing Limit reservation for transfer"));
        if (accountFailure.isEmpty()) limitCheck = limitAdapter.check(guard, reservation, at, date);
    }

    @Override
    public Optional<Failure> validateLockedState(Context context, LocalDate date) {
        return accountFailure.isPresent() ? accountFailure : limitCheck.failure();
    }

    @Override
    public FraudResult evaluateFraud(Context context, Instant at) {
        verifyTimeBudget();
        return fraud.evaluate(details.intent(), at);
    }

    @Override
    public Optional<Failure> moveMoney(Context context, TransactionStatus status) {
        Intent intent = details.intent();
        verifyTimeBudget();
        if (!fence.equals(claims.lockUsableFence(context))) throw new IllegalStateException("Stale money fence");
        // Both Account mutations use JDBC. No ORM mutations occur inside this savepoint.
        return jdbc.execute((ConnectionCallback<Optional<Failure>>) connection -> {
            var savepoint = connection.setSavepoint();
            var debit = accounts.debitConditional(intent.sourceId(), intent.amount());
            Optional<Failure> failure = switch (debit.outcome()) {
                case INSUFFICIENT_BALANCE -> Optional.of(Failure.INSUFFICIENT_BALANCE);
                case INACTIVE -> Optional.of(Failure.ACCOUNT_INACTIVE);
                case APPLIED -> {
                    debitBalance = debit.balanceAfter();
                    Optional<AccountBalance.CreditResult> credit;
                    try {
                        credit = accounts.creditUnderSavepoint(intent.destinationId(), intent.amount());
                    } catch (RuntimeException failureDuringCredit) {
                        throw new CreditFailure(failureDuringCredit);
                    }
                    if (credit.isEmpty()) {
                        connection.rollback(savepoint);
                        if (status.isRollbackOnly()) {
                            throw new CreditFailure(new UnexpectedRollbackException(
                                    "T2 is rollback-only after credit"));
                        }
                        // Account/usage locks predate the savepoint; JDBC rollback undid the debit.
                        debitBalance = null;
                        yield Optional.of(Failure.PROCESSING_ERROR);
                    }
                    creditBalance = credit.get().balanceAfter();
                    yield Optional.empty();
                }
            };
            connection.releaseSavepoint(savepoint);
            return failure;
        });
    }

    @Override
    public void postAndVerifyBalancedLedger(Context context) {
        Intent intent = details.intent();
        movements.recordMovement(new AccountTransferEvidence.Movement(intent.id(), intent.sourceId(),
                intent.destinationId(), intent.amount(), intent.currency(), debitBalance, creditBalance));
        var pair = ledger.postDoubleEntry(new PostingCommand(intent.id(), intent.sourceId(), intent.destinationId(),
                intent.amount(), intent.currency(), debitBalance.ledgerBalance(), creditBalance.ledgerBalance(),
                intent.correlationId()));
        if (pair.debitEntryId() <= 0 || pair.creditEntryId() <= 0 || pair.debitEntryId() == pair.creditEntryId()) {
            throw new IllegalStateException("Invalid Ledger posting reference");
        }
    }

    @Override
    public void settleCompletedReservation(Context context, Instant at, LocalDate date) {
        limitAdapter.finish(guard, reservation, Outcome.completed(), at, date, limitCheck);
    }

    @Override
    public void releaseFailedReservation(Context context, Instant at, LocalDate date) {
        limits.settleOrReleaseReservation(guard, reservation, TransferLimits.Outcome.FAILED, at, date, null);
    }

    @Override
    public void writeTerminalAndOutbox(Context context, Outcome outcome, Instant at) {
        this.terminalAt = at;
        Intent intent = details.intent();
        TransferFailureCode failure = outcome.failure().map(value -> TransferFailureCode.valueOf(value.name())).orElse(null);
        var source = accountQuery.findAccount(intent.sourceId());
        var destination = accountQuery.findAccount(intent.destinationId());
        var amount = new TransferAmount(intent.amount(), intent.currency());
        TransferView receipt = null;
        if (failure == null) {
            receipt = new TransferView(intent.id(), details.reference(), TransferStatus.COMPLETED, amount,
                    details.description(), masked(source.orElseThrow()), masked(destination.orElseThrow()),
                    intent.correlationId(), intent.createdAt(), at, null, null, null);
        }
        var encoded = responses.encode(details, receipt, failure, at);
        var saved = store.terminal(intent.id(), fence, terminalFenceState, at, failure == null ? null : failure.name(), encoded, ttl.get());
        response = new Committed(details.reference(), saved);
        preparedOutcome = outcome;
        if (source.isPresent() && destination.isPresent()) {
            notifications.enqueue(new TransferNotifications.Facts(intent.id(), details.reference(), intent.customerId(),
                    destination.get().customerId(), amount, source.get().accountNumberMasked(),
                    destination.get().accountNumberMasked(), intent.correlationId()), outcome);
        } else if (failure != TransferFailureCode.ACCOUNT_NOT_FOUND) {
            throw new IllegalStateException("Account projection unavailable for terminal notification");
        }
    }

    @Override
    public void flushBusinessWrites() {
        verifyTimeBudget();
        flush.run();
        long remaining = Math.max(1, statementMs - elapsedMs());
        store.setTimeouts(Math.min(remaining, lockMs), remaining);
    }

    @Override
    public void recordFraudFlagAudit(Context context, Flag flag, Instant at) {
        audit.recordFraudFlag(details, flag, at);
    }

    @Override
    public void recordTerminalAudit(Context context, Outcome outcome, Instant at) {
        audit.recordTerminal(details, outcome, at);
    }

    void finalizeAbortedCredit(Context context, AttemptFence expected, Reservation provenPending, LocalDate date,
            Instant at) {
        lockStepUpGuard(context.customerId());
        terminalFenceState = State.FAILURE_PENDING;
        fence = claims.lockUsableFence(context, terminalFenceState);
        if (!fence.equals(expected)) throw new IllegalStateException("Stale Tfail fence");
        reservation = provenPending;
        Outcome outcome = Outcome.failed(Failure.PROCESSING_ERROR);
        releaseFailedReservation(context, at, date);
        writeTerminalAndOutbox(context, outcome, at);
        flushBusinessWrites();
        try {
            recordTerminalAudit(context, outcome, at);
        } catch (RuntimeException failure) {
            throw new AuditWriteException(failure);
        }
        readyToCommit();
    }

    @Override
    public void readyToCommit() {
        verifyTimeBudget();
        readyToCommit = true;
    }

    private void verifyTimeBudget() {
        if (elapsedMs() >= statementMs) throw new TransactionTimedOutException("T2 time budget exhausted");
    }

    private long elapsedMs() {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public void afterCompletion(int status) {
        this.completion = status;
    }

    boolean preparedForCommit() { return readyToCommit; }

    boolean committed() { return completion == STATUS_COMMITTED; }

    boolean rolledBack() { return completion == STATUS_ROLLED_BACK; }

    boolean hasCommittedResponse() {
        return response != null && store.hasCommittedResponse(details.intent().id(), fence, terminalAt, response.response());
    }

    Outcome preparedOutcome() { return Objects.requireNonNull(preparedOutcome, "terminal outcome"); }

    Committed response() { return Objects.requireNonNull(response, "terminal response"); }

    private static MaskedAccount masked(AccountSnapshot value) {
        return new MaskedAccount(value.accountId(), value.accountNumberMasked(), value.accountNumberLast4());
    }
}
