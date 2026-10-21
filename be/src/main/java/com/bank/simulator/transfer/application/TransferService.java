package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferOtpRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransferService {
    private static final String OPERATION = "createTransfer";
    private static final Duration OTP_TTL = Duration.ofSeconds(120);
    private static final int OTP_MAX_ATTEMPTS = 5;

    private final AccountModuleApi accounts;
    private final TransferJdbcRepository transfers;
    private final IdempotencyJdbcRepository idempotency;
    private final TransferOtpRepository transferOtp;
    private final OtpHashingService otpHashing;
    private final OtpChallengeRepository otpRepository;
    private final OtpSender sender;
    private final IdentityModuleApi identities;
    private final AuditWriter audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public TransferService(AccountModuleApi accounts, TransferJdbcRepository transfers,
                           IdempotencyJdbcRepository idempotency, TransferOtpRepository transferOtp,
                           OtpHashingService otpHashing, OtpChallengeRepository otpRepository, OtpSender sender,
                           IdentityModuleApi identities, AuditWriter audit, Clock clock,
                           ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.transferOtp = transferOtp;
        this.otpHashing = otpHashing;
        this.otpRepository = otpRepository;
        this.sender = sender;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
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
        CreateResult result = transactions.execute(status -> createTransaction(actor, customer, command, amount, requestHash, idempotencyKey));
        if (result.replayed() && result.challengeRequired()) {
            var row = transfers.find(result.transferId());
            int remaining = Math.max(0, (int) Duration.between(clock.instant(), row.expiresAt()).toSeconds());
            return CreateResult.challengeReplay(row, result.idempotencyId(), remaining);
        }
        if (!result.challengeRequired() || result.replayed()) return result.publicResult();

        try {
            sender.send(new com.bank.simulator.identity.infrastructure.otp.OtpMessage(
                    result.challengeId(), result.phone(), result.otpCode(), result.expiresAt()));
            audit.record(actor.userId(), "CUSTOMER", "TRANSFER_OTP_REQUESTED", "TRANSFER", result.transferId(),
                    "SUCCESS", null, "Transfer step-up OTP requested.");
            idempotency.complete(result.idempotencyId(), 200, result.challengeJson(), result.transferId());
            return result.publicResult();
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> {
                transfers.fail(result.transferId(), "OTP_DISPATCH_FAILED", clock.instant());
                transferOtp.invalidate(result.challengeId(), clock.instant());
            });
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Transfer OTP delivery failed.");
        }
    }

    private CreateResult createTransaction(AuthenticatedActor actor, IdentityModuleApi.CustomerView customer,
                                           CreateCommand command, BigDecimal amount, String requestHash, String key) {
        UUID customerId = customer.customerId();
        Instant now = clock.instant();

        IdempotencyJdbcRepository.IdempotencyRecord existing = idempotency.find(actor.userId(), OPERATION, key);
        UUID idempotencyId;
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }
        var claim = idempotency.createOrFind(actor.userId(), OPERATION, key, requestHash, now, now.plus(Duration.ofHours(24)));
        idempotencyId = claim.record().id();
        if (!claim.created()) {
            return replayOrConflict(claim.record(), requestHash);
        }

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
            var owner = identities.findCustomerById(source.customerId());
            if (owner == null) throw accountNotFound();
            UUID challengeId = UUID.randomUUID();
            UUID transferId = transfers.create(source.id(), destination.id(), amount.toPlainString(), command.currency(),
                    "AWAITING_OTP", command.memo(), challengeId, idempotencyId, now, now.plus(OTP_TTL));
            String code = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
            Instant expiresAt = now.plus(OTP_TTL);
            transferOtp.create(transferId, challengeId, owner.phone(), otpHashing.encode(code), now, expiresAt);
            return CreateResult.challenge(transferId, idempotencyId, challengeId, owner.phone(), code, expiresAt);
        }

        UUID transferId = transfers.create(source.id(), destination.id(), amount.toPlainString(), command.currency(),
                "COMPLETED", command.memo(), null, idempotencyId, now, null);
        accounts.debit(source.id(), amount);
        accounts.credit(destination.id(), amount);
        transfers.complete(transferId, now);
        var transfer = transfers.find(transferId);
        idempotency.complete(idempotencyId, 201, transferJson(transfer), transferId);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        publishRiskEvent(transfer, source.id(), now);
        return CreateResult.transfer(transfer, false);
    }

    private CreateResult replayOrConflict(IdempotencyJdbcRepository.IdempotencyRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with another request.");
        }
        if (existing.resourceId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
        }
        var prior = transfers.find(existing.resourceId());
        if (prior != null) {
            if ("AWAITING_OTP".equals(prior.status())) {
                int remaining = Math.max(0, (int) Duration.between(clock.instant(), prior.expiresAt()).toSeconds());
                return CreateResult.challengeReplay(prior, existing.id(), remaining);
            }
            return CreateResult.transfer(prior, true);
        }
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
    }

    private void publishRiskEvent(TransferJdbcRepository.TransferRow transfer, UUID sourceAccountId, Instant completedAt) {
        events.publishEvent(new TransferCommittedEvent(transfer.id(), sourceAccountId, transfer.amount(), completedAt, correlationId()));
    }

    public boolean confirm(AuthenticatedActor actor, UUID transferId, String otp) {
        requireCustomer(actor);
        ConfirmOutcome outcome = transactions.execute(status -> confirmTransaction(actor, transferId, otp));
        if (outcome.error() != null) throw outcome.error();
        return outcome.replayed();
    }

    private ConfirmOutcome confirmTransaction(AuthenticatedActor actor, UUID transferId, String otp) {
        var transfer = transfers.lock(transferId);
        if (transfer == null) return ConfirmOutcome.error(transferNotFound());
        if ("COMPLETED".equals(transfer.status())) return ConfirmOutcome.replay();
        if (!"AWAITING_OTP".equals(transfer.status())) {
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer is not awaiting OTP."));
        }
        UUID customerId = customerFor(actor).customerId();
        Instant now = clock.instant();
        AccountModuleApi.LockedAccountPair pair = accounts.lockAccountsAscending(transfer.sourceAccountId(),
                transfer.destinationAccountId());
        if (pair == null || !pair.source().customerId().equals(customerId)) return ConfirmOutcome.error(transferNotFound());
        if (transfer.expiresAt() == null || !transfer.expiresAt().isAfter(now)) {
            transfers.expire(transferId, now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "TRANSFER_EXPIRED", "Transfer OTP expired."));
        }
        var challenge = transferOtp.lock(transfer.otpChallengeId(), now);
        if (challenge == null || challenge.consumedAt() != null || challenge.invalidatedAt() != null) {
            return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
        }
        if (!challenge.expiresAt().isAfter(now)) {
            transfers.expire(transferId, now);
            transferOtp.invalidate(challenge.id(), now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "TRANSFER_EXPIRED", "Transfer OTP expired."));
        }
        if (!otpHashing.matches(otp, challenge.otpHash())) {
            int attempts = challenge.attempts() + 1;
            transferOtp.updateAttempts(challenge.id(), attempts, attempts >= OTP_MAX_ATTEMPTS ? now : null);
            if (attempts >= OTP_MAX_ATTEMPTS) {
                transfers.fail(transferId, "OTP_ATTEMPTS_EXCEEDED", now);
                return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer OTP attempts exceeded."));
            }
            return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
        }
        var source = pair.source();
        var destination = pair.destination();
        if (!source.customerId().equals(customerId)) return ConfirmOutcome.error(transferNotFound());
        if (!eligible(source) || !eligible(destination) || !"VND".equals(transfer.currency())) {
            transfers.fail(transferId, "ACCOUNT_NOT_ELIGIBLE", now);
            transferOtp.invalidate(challenge.id(), now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE", "Account is not eligible for transfer."));
        }
        if (new BigDecimal(source.balance()).compareTo(new BigDecimal(transfer.amount())) < 0) {
            transfers.fail(transferId, "INSUFFICIENT_FUNDS", now);
            transferOtp.invalidate(challenge.id(), now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Available balance is insufficient."));
        }
        transferOtp.consume(challenge.id(), now);
        accounts.debit(source.id(), new BigDecimal(transfer.amount()));
        accounts.credit(destination.id(), new BigDecimal(transfer.amount()));
        transfers.complete(transferId, now);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        publishRiskEvent(transfers.find(transferId), source.id(), now);
        return ConfirmOutcome.success();
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

    private ApiException transferNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer is not available.");
    }

    private String transferJson(TransferJdbcRepository.TransferRow row) {
        return "{\"transferId\":\"" + row.id() + "\",\"status\":\"" + row.status()
                + "\",\"sourceAccountId\":\"" + row.sourceAccountId() + "\",\"destinationAccountId\":\""
                + row.destinationAccountId() + "\",\"amount\":\"" + row.amount() + "\",\"currency\":\""
                + row.currency() + "\",\"createdAt\":\"" + row.createdAt() + "\"}";
    }

    public record CreateCommand(UUID sourceAccountId, UUID destinationAccountId, String amount, String currency,
                                String pin, String memo) {}

    private record ConfirmOutcome(boolean replayed, ApiException error) {
        static ConfirmOutcome success() { return new ConfirmOutcome(false, null); }
        static ConfirmOutcome replay() { return new ConfirmOutcome(true, null); }
        static ConfirmOutcome error(ApiException error) { return new ConfirmOutcome(false, error); }
    }

    public record CreateResult(boolean challengeRequired, UUID transferId, String status, String message,
                               Instant expiresAt, int expiresInSeconds, String failureCode, boolean replayed,
                               UUID idempotencyId, UUID challengeId, String phone, String otpCode) {
        static CreateResult challenge(UUID transferId, UUID idempotencyId, UUID challengeId, String phone,
                                      String otpCode, Instant expiresAt) {
            return new CreateResult(true, transferId, "AWAITING_OTP", "Transfer exceeds 5,000,000 VND. OTP sent to registered phone number.",
                    expiresAt, (int) OTP_TTL.toSeconds(), null, false, idempotencyId, challengeId, phone, otpCode);
        }
        static CreateResult transfer(TransferJdbcRepository.TransferRow row, boolean replayed) {
            return new CreateResult(false, row.id(), row.status(), null, row.expiresAt(), 0, row.failureCode(), replayed,
                    null, row.otpChallengeId(), null, null);
        }
        static CreateResult challengeReplay(TransferJdbcRepository.TransferRow row, UUID idempotencyId, int ttl) {
            return new CreateResult(true, row.id(), row.status(), "Transfer awaits OTP confirmation.", row.expiresAt(), ttl,
                    row.failureCode(), true, idempotencyId, row.otpChallengeId(), null, null);
        }
        CreateResult publicResult() {
            return new CreateResult(challengeRequired, transferId, status, message, expiresAt, expiresInSeconds,
                    failureCode, replayed, null, null, null, null);
        }
        String challengeJson() {
            return "{\"transferId\":\"" + transferId + "\",\"status\":\"" + status
                    + "\",\"expiresAt\":\"" + expiresAt + "\",\"expiresInSeconds\":" + expiresInSeconds + "}";
        }
    }
}
