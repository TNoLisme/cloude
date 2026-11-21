package com.simulatedbank.banking.transfer.adapter.out.integration;

import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.ACCOUNT_INACTIVE;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.ACCOUNT_NOT_FOUND;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.CUSTOMER_INACTIVE;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.INSUFFICIENT_BALANCE;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.LIMIT_EXCEEDED;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.SAME_ACCOUNT;
import static com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason.SOURCE_NOT_OWNED;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountQuery.AccountViewQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence.PendingInsert;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence.PendingTransfer;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate.Command;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Accepted;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Preflight;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Rejected;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Request;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Stored;
import com.simulatedbank.banking.transfer.application.TransferReferenceGenerator;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;

/** Bridges T0/T1 to provider APIs. A fresh Intent instance carries state for each T1 transaction. */
public final class TransferCreateAdapters implements TransferPreflightCandidate.Ports {
    private final AccountQuery accounts;
    private final CustomerQuery customers;
    private final TransferLimits limits;
    private final TransferLimitAdapter limitAdapter;
    private final TransferPersistence transfers;
    private final TransferIdentityAdapter identity;
    private final TransferAuditAdapter audit;
    private final PlatformTransactionManager manager;
    private final TransferTelemetry telemetry;
    private final PostCommitSettlement settlement;
    private final ZoneId zone;
    private final int ttlHours;
    private final TransferLimits.StepUpPolicy stepUpPolicy;
    private final TransferReferenceGenerator references = new TransferReferenceGenerator();

    public TransferCreateAdapters(AccountQuery accounts, CustomerQuery customers, TransferLimits limits,
            TransferLimitAdapter limitAdapter, TransferPersistence transfers, TransferIdentityAdapter identity,
            TransferAuditAdapter audit, PlatformTransactionManager manager, TransferTelemetry telemetry,
            PostCommitSettlement settlement, ZoneId zone, int ttlHours, TransferLimits.StepUpPolicy stepUpPolicy) {
        this.accounts = accounts;
        this.customers = customers;
        this.limits = limits;
        this.limitAdapter = limitAdapter;
        this.transfers = transfers;
        this.identity = identity;
        this.audit = audit;
        this.manager = manager;
        this.telemetry = telemetry;
        this.settlement = settlement;
        this.zone = zone;
        this.ttlHours = ttlHours;
        this.stepUpPolicy = stepUpPolicy;
    }

    @Override
    public Optional<Stored> findExistingOnPrimary(String key) {
        return transfers.findExistingOnPrimary(key);
    }

    @Override
    public OptionalLong resolveAccountNumber(String accountNumber) {
        var destination = accounts.resolveDestination(accountNumber);
        return destination.isEmpty() ? OptionalLong.empty() : OptionalLong.of(destination.get().accountId());
    }

    @Override
    public Optional<RejectionReason> checkCustomer(Request request) {
        var customer = customers.findByUserId(request.actorId())
                .filter(binding -> binding.customerId() == request.customerId());
        if (customer.isEmpty()) return Optional.of(SOURCE_NOT_OWNED);
        return customer.get().status() == CustomerQuery.Status.ACTIVE
                ? Optional.empty() : Optional.of(CUSTOMER_INACTIVE);
    }

    @Override
    public Preflight preflight(Request request, String requestHash, long destinationId) {
        var payload = request.payload();
        var sourceSnapshot = accounts.findAccount(payload.sourceAccountId());
        if (sourceSnapshot.isEmpty()) return new Rejected(ACCOUNT_NOT_FOUND);
        if (sourceSnapshot.get().customerId() != request.customerId()) return new Rejected(SOURCE_NOT_OWNED);
        if (payload.sourceAccountId() == destinationId) return new Rejected(SAME_ACCOUNT);

        AccountQuery.AccountView source;
        try {
            source = accounts.getAccountView(AccountViewQuery.forOwner(payload.sourceAccountId(),
                    request.customerId(), request.correlationId()));
        } catch (BankingBusinessException failure) {
            return switch (failure.code()) {
                case ACCOUNT_NOT_FOUND -> new Rejected(ACCOUNT_NOT_FOUND);
                case AUTH_FORBIDDEN -> new Rejected(SOURCE_NOT_OWNED);
                default -> throw failure;
            };
        }
        var destination = accounts.findAccount(destinationId);
        if (destination.isEmpty()) return new Rejected(ACCOUNT_NOT_FOUND);
        var amount = payload.amount();
        if (source.status() != AccountQuery.Status.ACTIVE || destination.get().status() != AccountQuery.Status.ACTIVE
                || !amount.currency().equals(source.currency()) || !amount.currency().equals(destination.get().currency())) {
            return new Rejected(ACCOUNT_INACTIVE);
        }
        if (source.availableBalance().compareTo(amount.value()) < 0) return new Rejected(INSUFFICIENT_BALANCE);

        var asOf = transfers.primaryTime();
        var scopes = limits.getUsageSnapshot(request.customerId(), source.accountId(), asOf,
                asOf.atZone(zone).toLocalDate()).scopes();
        if (scopes.stream().filter(scope -> scope.accountId() == null).count() != 1
                || scopes.stream().filter(scope -> scope.accountId() != null).count() > 1) {
            throw new IllegalStateException("Limit snapshot configuration is unavailable");
        }
        if (scopes.stream().anyMatch(scope -> amount.value().compareTo(scope.perTransactionLimit()) > 0
                || amount.value().compareTo(scope.remaining()) > 0)) {
            return new Rejected(LIMIT_EXCEEDED);
        }

        var transfer = new TransferIdentity(references.generate(), request.customerId(), request.actorId(),
                source.accountId(), destinationId, null);
        return new Accepted(new Command(transfer, amount, payload.description(), request.correlationId(),
                request.idempotencyKey(), requestHash, null));
    }

    @Override
    public TransferIntentCandidate.Result createIntent(Command command) {
        return new TransferIntentCandidate(manager, new Intent(), telemetry).create(command);
    }

    @Override
    public Settlement settleCreated(long transferId) {
        return settlement.settle(transferId);
    }

    @FunctionalInterface
    public interface PostCommitSettlement {
        Settlement settle(long transferId);
    }

    private final class Intent implements TransferIntentCandidate.Ports {
        private TransferLimits.Guard guard;
        private TransferLimitAdapter.Assessment limitDecision;
        private PendingTransfer pending;

        @Override public void lockStepUpGuard(long customerId) {
            guard = limitAdapter.lockCustomer(customerId);
        }

        @Override public Optional<IdempotencyDecision.Existing> findExistingOnPrimary(String key) {
            return transfers.findExistingOnPrimary(key).map(Stored::identity);
        }

        @Override public TransferIntentCandidate.Assessment evaluateStepUpUnderGuard(Command command) {
            limitDecision = limitAdapter.assess(guard, command, stepUpPolicy);
            return limitDecision.candidateView();
        }

        @Override public UUID findVerifiedChallenge(Command command) {
            return identity.findVerifiedBindingIfPresent(TransferIdentityAdapter.request(command))
                    .map(challenge -> challenge.challengeRef()).orElse(null);
        }

        @Override public long insertPendingAndKey(Command command) {
            var ttl = new IdempotencyRetention.Snapshot(ttlHours * 3600L, "transfer-ttl-hours-" + ttlHours);
            try {
                pending = transfers.insertPending(new PendingInsert(command.identity(), command.amount(),
                        command.description(), command.correlationId(), command.idempotencyKey(), command.requestHash(),
                        ttl.seconds(), ttl.version()));
                return pending.id();
            } catch (TransferPersistence.DuplicateIdempotencyKeyException duplicate) {
                throw new TransferIntentCandidate.DuplicateKeyRace();
            }
        }

        @Override public void reserveForPendingTransfer(Command command, TransferIntentCandidate.Assessment assessment,
                long transferId) {
            var reserved = limitAdapter.reserve(guard, command, pending, limitDecision);
            transfers.recordRecoveryBasis(transferId, reserved.policyVersion(), reserved.t1Date());
        }

        @Override public long consumeVerified(Command command, UUID challengeRef) {
            return identity.consume(command, challengeRef);
        }

        @Override public void attachChallenge(long transferId, long challengeId) {
            transfers.attachChallenge(transferId, challengeId);
        }

        @Override public void flushBusinessWrites() {
            // Every T1 write uses JDBC and has executed before reaching the Audit boundary.
        }

        @Override public void recordCreated(TransferIdentity transfer, TransferAmount amount, String correlationId,
                long transferId) {
            audit.recordCreated(transfer, amount, correlationId, pending.createdAt());
        }
    }
}
