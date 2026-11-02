package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.domain.TransferExpirationPolicy;
import com.bank.simulator.transfer.domain.TransferPolicy;
import com.bank.simulator.transfer.domain.TransferStatus;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class CreateTransferService {
    private static final String OPERATION = "createTransfer";
    private static final String OTP_PURPOSE = "TRANSFER_STEP_UP";

    private final AccountModuleApi accounts;
    private final TransferJdbcRepository transfers;
    private final IdempotencyJdbcRepository idempotency;
    private final IdentityModuleApi identities;
    private final AuditWriter audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final TransferFailureInjector failureInjector;

    public CreateTransferService(AccountModuleApi accounts, TransferJdbcRepository transfers,
                                 IdempotencyJdbcRepository idempotency, IdentityModuleApi identities,
                                 AuditWriter audit, Clock clock, ApplicationEventPublisher events,
                                 PlatformTransactionManager transactionManager,
                                 TransferFailureInjector failureInjector) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.failureInjector = failureInjector;
    }

    public CreateResult create(AuthenticatedActor actor, CreateCommand command, String idempotencyKey) {
        requireCustomer(actor);
        IdentityModuleApi.CustomerView customer = customerFor(actor);
        validate(command, idempotencyKey);
        BigDecimal amount = TransferPolicy.parseAmount(command.amount());

        IdentityModuleApi.PinVerificationResult pin = identities.verifyPin(customer.customerId(), command.pin());
        if (pin != IdentityModuleApi.PinVerificationResult.VALID) throw pinError(pin);

        String requestHash = TransferPolicy.payloadHash(command.sourceAccountId(), command.destinationAccountId(),
                amount, command.currency(), command.memo());
        CreateResult result = transactions.execute(status ->
                createTransaction(actor, customer, command, amount, requestHash, idempotencyKey));
        if (result.replayed()) return result.publicResult();
        if (!result.challengeRequired()) return result.publicResult();
        return dispatchOtp(actor, customer, result);
    }

    private CreateResult createTransaction(AuthenticatedActor actor, IdentityModuleApi.CustomerView customer,
                                           CreateCommand command, BigDecimal amount, String requestHash, String key) {
        UUID customerId = customer.customerId();
        Instant now = clock.instant();

        IdempotencyJdbcRepository.IdempotencyRecord existing = idempotency.find(actor.userId(), OPERATION, key);
        if (existing != null) return replayOrConflict(existing, requestHash);

        IdempotencyJdbcRepository.IdempotencyClaim claim = claim(actor.userId(), key, requestHash, now);
        UUID idempotencyId = claim.record().id();
        if (!claim.created()) return replayOrConflict(claim.record(), requestHash);

        AccountModuleApi.LockedAccountPair pair = accounts.lockAccountsAscending(command.sourceAccountId(),
                command.destinationAccountId());
        if (pair == null) throw accountNotFound();
        var source = pair.source();
        var destination = pair.destination();
        if (!source.customerId().equals(customerId)) throw accountNotFound();
        if (!eligible(source) || !eligible(destination)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE", "Source or destination account is not eligible for transfer.");
        }
        if (new BigDecimal(source.balance()).compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Available balance is insufficient.");
        }

        if (TransferPolicy.requiresOtp(amount)) {
            IdentityModuleApi.OtpIssue issue = identities.issueOtp(customerId, OTP_PURPOSE);
            UUID transferId = transfers.create(source.id(), destination.id(), amount.toPlainString(), command.currency(),
                    TransferStatus.AWAITING_OTP.name(), command.memo(), issue.challengeId(), idempotencyId, now,
                    issue.expiresAt());
            int expiresInSeconds = (int) Duration.between(now, issue.expiresAt()).toSeconds();
            idempotency.complete(idempotencyId, 200,
                    awaitingJson(transferId, issue.expiresAt(), expiresInSeconds), transferId);
            return CreateResult.challenge(transferId, idempotencyId, issue, expiresInSeconds);
        }

        UUID transferId = transfers.create(source.id(), destination.id(), amount.toPlainString(), command.currency(),
                TransferStatus.COMPLETED.name(), command.memo(), null, idempotencyId, now, null);
        accounts.debit(source.id(), amount);
        failureInjector.afterDebitBeforeCredit();
        accounts.credit(destination.id(), amount);
        var transfer = transfers.find(transferId);
        idempotency.complete(idempotencyId, 201, transferJson(transfer), transferId);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        publishRiskEvent(transfer, source.id(), now);
        return CreateResult.transfer(transfer, false);
    }

    private IdempotencyJdbcRepository.IdempotencyClaim claim(UUID actorId, String key, String requestHash, Instant now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            IdempotencyJdbcRepository.IdempotencyClaim claim = idempotency.createOrFind(actorId, OPERATION, key,
                    requestHash, now, now.plus(Duration.ofHours(24)));
            if (claim.record() != null) return claim;
        }
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
    }

    private CreateResult dispatchOtp(AuthenticatedActor actor, IdentityModuleApi.CustomerView customer,
                                     CreateResult result) {
        IdentityModuleApi.OtpIssue issue = new IdentityModuleApi.OtpIssue(result.challengeId(), customer.customerId(),
                result.phone(), result.otpCode(), result.expiresAt());
        try {
            identities.dispatchOtp(issue);
        } catch (RuntimeException exception) {
            compensateDispatchFailure(result.transferId(), result.challengeId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Transfer OTP delivery failed.");
        }
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_OTP_REQUESTED", "TRANSFER", result.transferId(),
                "SUCCESS", null, "Transfer step-up OTP requested.");
        return result.publicResult();
    }

    private void compensateDispatchFailure(UUID transferId, UUID challengeId) {
        transactions.executeWithoutResult(status -> {
            transfers.lock(transferId);
            transfers.fail(transferId, "OTP_DISPATCH_FAILED", clock.instant());
            identities.invalidateOtp(challengeId);
        });
    }

    private CreateResult replayOrConflict(IdempotencyJdbcRepository.IdempotencyRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with another request.");
        }
        if (existing.resourceId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
        }
        var prior = transfers.find(existing.resourceId());
        if (prior == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
        }
        if (TransferStatus.AWAITING_OTP.name().equals(prior.status())) {
            String effective = TransferExpirationPolicy.resolveEffectiveStatus(TransferStatus.AWAITING_OTP,
                    prior.expiresAt(), clock.instant()).name();
            if (!TransferStatus.AWAITING_OTP.name().equals(effective)) return CreateResult.terminal(prior, true);
            int remaining = Math.max(0, (int) Duration.between(clock.instant(), prior.expiresAt()).toSeconds());
            return CreateResult.challengeReplay(prior, existing.id(), remaining);
        }
        return CreateResult.transfer(prior, true);
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

    private void validate(CreateCommand command, String key) {
        if (command == null || command.sourceAccountId() == null || command.destinationAccountId() == null
                || command.amount() == null || !"VND".equals(command.currency()) || command.pin() == null
                || !command.pin().matches("[0-9]{6}")
                || command.memo() != null && command.memo().length() > 140) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer request is invalid.");
        }
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Source and destination accounts must differ.");
        }
        if (key == null || key.length() < 16 || key.length() > 128 || !key.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency-Key must be 16-128 printable ASCII characters.");
        }
    }

    private void requireCustomer(AuthenticatedActor actor) {
        if (!actor.hasRole("CUSTOMER")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
    }

    private ApiException pinError(IdentityModuleApi.PinVerificationResult pin) {
        return switch (pin) {
            case LOCKED -> new ApiException(HttpStatus.FORBIDDEN, "PIN_LOCKED",
                    "Transaction PIN is locked due to repeated failed attempts.");
            case INVALID -> new ApiException(HttpStatus.BAD_REQUEST, "PIN_INVALID", "Transaction PIN is invalid.");
            case NOT_SET -> new ApiException(HttpStatus.BAD_REQUEST, "PIN_NOT_SET", "Transaction PIN is not configured.");
            case VALID -> throw new IllegalStateException("Valid PIN cannot produce an error");
        };
    }

    private boolean eligible(AccountModuleApi.LockedAccount account) {
        return account != null && "ACTIVE".equals(account.status()) && "VND".equals(account.currency());
    }

    private ApiException accountNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Source or destination account was not found.");
    }

    private String transferJson(TransferJdbcRepository.TransferRow row) {
        return "{\"transferId\":\"" + row.id() + "\",\"status\":\"" + row.status()
                + "\",\"sourceAccountId\":\"" + row.sourceAccountId() + "\",\"destinationAccountId\":\""
                + row.destinationAccountId() + "\",\"amount\":\"" + row.amount() + "\",\"currency\":\""
                + row.currency() + "\",\"createdAt\":\"" + row.createdAt() + "\"}";
    }

    private String awaitingJson(UUID transferId, Instant expiresAt, int expiresInSeconds) {
        return "{\"transferId\":\"" + transferId + "\",\"status\":\"AWAITING_OTP\",\"expiresAt\":\"" + expiresAt
                + "\",\"expiresInSeconds\":" + expiresInSeconds + "}";
    }

    public record CreateCommand(UUID sourceAccountId, UUID destinationAccountId, String amount, String currency,
                                String pin, String memo) {}

    public record CreateResult(boolean challengeRequired, UUID transferId, String status, String message,
                               Instant expiresAt, int expiresInSeconds, String failureCode, boolean replayed,
                               UUID idempotencyId, UUID challengeId, String phone, String otpCode) {
        static CreateResult challenge(UUID transferId, UUID idempotencyId, IdentityModuleApi.OtpIssue issue,
                                      int expiresInSeconds) {
            return new CreateResult(true, transferId, TransferStatus.AWAITING_OTP.name(),
                    "Transfer exceeds 5,000,000 VND. OTP sent to registered phone number.",
                    issue.expiresAt(), expiresInSeconds, null, false, idempotencyId, issue.challengeId(),
                    issue.phone(), issue.code());
        }
        static CreateResult transfer(TransferJdbcRepository.TransferRow row, boolean replayed) {
            return new CreateResult(false, row.id(), row.status(), null, row.expiresAt(), 0, row.failureCode(), replayed,
                    null, row.otpChallengeId(), null, null);
        }
        static CreateResult terminal(TransferJdbcRepository.TransferRow row, boolean replayed) {
            return new CreateResult(false, row.id(), TransferStatus.EXPIRED.name(), null, row.expiresAt(), 0,
                    row.failureCode(), replayed, null, row.otpChallengeId(), null, null);
        }
        static CreateResult challengeReplay(TransferJdbcRepository.TransferRow row, UUID idempotencyId, int ttl) {
            return new CreateResult(true, row.id(), row.status(), "Transfer awaits OTP confirmation.", row.expiresAt(), ttl,
                    row.failureCode(), true, idempotencyId, row.otpChallengeId(), null, null);
        }
        CreateResult publicResult() {
            return new CreateResult(challengeRequired, transferId, status, message, expiresAt, expiresInSeconds,
                    failureCode, replayed, null, null, null, null);
        }
    }
}
