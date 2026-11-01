package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.domain.TransferStatus;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ConfirmTransferOtpService {
    private final AccountModuleApi accounts;
    private final TransferJdbcRepository transfers;
    private final IdentityModuleApi identities;
    private final AuditWriter audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final TransferFailureInjector failureInjector;

    public ConfirmTransferOtpService(AccountModuleApi accounts, TransferJdbcRepository transfers,
                                     IdentityModuleApi identities, AuditWriter audit, Clock clock,
                                     ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                     TransferFailureInjector failureInjector) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.failureInjector = failureInjector;
    }

    public ConfirmResult confirm(AuthenticatedActor actor, UUID transferId, String otp) {
        requireCustomer(actor);
        IdentityModuleApi.CustomerView customer = customerFor(actor);
        ConfirmOutcome outcome = transactions.execute(status ->
                confirmTransaction(actor, customer.customerId(), transferId, otp));
        if (outcome.error() != null) throw outcome.error();
        return new ConfirmResult(outcome.replayed());
    }

    private ConfirmOutcome confirmTransaction(AuthenticatedActor actor, UUID customerId, UUID transferId, String otp) {
        var transfer = transfers.lock(transferId);
        if (transfer == null) return ConfirmOutcome.error(transferNotFound());
        if (!customerId.equals(accounts.ownerCustomerId(transfer.sourceAccountId()))) {
            return ConfirmOutcome.error(transferNotFound());
        }
        if (TransferStatus.COMPLETED.name().equals(transfer.status())) return ConfirmOutcome.replay();
        if (TransferStatus.EXPIRED.name().equals(transfer.status())) return ConfirmOutcome.error(expired());
        if (!TransferStatus.AWAITING_OTP.name().equals(transfer.status())) {
            return ConfirmOutcome.error(stateConflict("Transfer is not awaiting OTP."));
        }

        Instant now = clock.instant();
        if (transfer.expiresAt() == null || now.isAfter(transfer.expiresAt())) {
            transfers.expire(transferId, now);
            return ConfirmOutcome.error(expired());
        }

        IdentityModuleApi.OtpConsumeResult otpResult = identities.consumeOtp(transfer.otpChallengeId(), otp);
        switch (otpResult.outcome()) {
            case INVALID -> {
                return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
            }
            case ATTEMPTS_EXCEEDED -> {
                transfers.fail(transferId, "OTP_ATTEMPTS_EXCEEDED", now);
                return ConfirmOutcome.error(stateConflict("Transfer OTP attempts exceeded."));
            }
            case EXPIRED -> {
                transfers.expire(transferId, now);
                return ConfirmOutcome.error(expired());
            }
            case INVALIDATED, ALREADY_USED, NOT_FOUND -> {
                return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
            }
            case VALID -> {
            }
        }

        AccountModuleApi.LockedAccountPair pair = accounts.lockAccountsAscending(transfer.sourceAccountId(),
                transfer.destinationAccountId());
        if (pair == null) {
            fail(transferId, transfer.otpChallengeId(), "ACCOUNT_NOT_ELIGIBLE", now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE",
                    "Account is not eligible for transfer."));
        }
        var source = pair.source();
        var destination = pair.destination();
        if (!source.customerId().equals(customerId)) return ConfirmOutcome.error(transferNotFound());
        if (!eligible(source) || !eligible(destination) || !"VND".equals(transfer.currency())) {
            fail(transferId, transfer.otpChallengeId(), "ACCOUNT_NOT_ELIGIBLE", now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE",
                    "Account is not eligible for transfer."));
        }
        if (new BigDecimal(source.balance()).compareTo(new BigDecimal(transfer.amount())) < 0) {
            fail(transferId, transfer.otpChallengeId(), "INSUFFICIENT_FUNDS", now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS",
                    "Available balance is insufficient."));
        }

        accounts.debit(source.id(), new BigDecimal(transfer.amount()));
        failureInjector.afterDebitBeforeCredit();
        accounts.credit(destination.id(), new BigDecimal(transfer.amount()));
        transfers.complete(transferId, now);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        publishRiskEvent(transfers.find(transferId), source.id(), now);
        return ConfirmOutcome.success();
    }

    private void fail(UUID transferId, UUID challengeId, String failureCode, Instant now) {
        transfers.fail(transferId, failureCode, now);
        identities.invalidateOtp(challengeId);
    }

    private void publishRiskEvent(TransferJdbcRepository.TransferRow transfer, UUID sourceAccountId, Instant completedAt) {
        events.publishEvent(new TransferCommittedEvent(transfer.id(), sourceAccountId, transfer.amount(), completedAt, correlationId()));
    }

    private UUID correlationId() {
        String value = org.slf4j.MDC.get("correlationId");
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private IdentityModuleApi.CustomerView customerFor(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        return customer;
    }

    private void requireCustomer(AuthenticatedActor actor) {
        if (!actor.hasRole("CUSTOMER")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
    }

    private boolean eligible(AccountModuleApi.LockedAccount account) {
        return account != null && "ACTIVE".equals(account.status()) && "VND".equals(account.currency());
    }

    private ApiException transferNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer is not available.");
    }

    private ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "TRANSFER_EXPIRED", "Transfer OTP expired.");
    }

    private ApiException stateConflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", detail);
    }

    public record ConfirmResult(boolean replayed) {}

    private record ConfirmOutcome(boolean replayed, ApiException error) {
        static ConfirmOutcome success() { return new ConfirmOutcome(false, null); }
        static ConfirmOutcome replay() { return new ConfirmOutcome(true, null); }
        static ConfirmOutcome error(ApiException error) { return new ConfirmOutcome(false, error); }
    }
}
