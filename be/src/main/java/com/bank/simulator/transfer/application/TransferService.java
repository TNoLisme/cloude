package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.application.PinCredentialService;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferOtpRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransferService {
    private static final String OPERATION = "createTransfer";
    private static final Duration OTP_TTL = Duration.ofSeconds(120);
    private static final int OTP_MAX_ATTEMPTS = 5;

    private final AccountJdbcRepository accounts;
    private final TransferJdbcRepository transfers;
    private final IdempotencyJdbcRepository idempotency;
    private final TransferOtpRepository transferOtp;
    private final IdentityJdbcRepository identities;
    private final OtpHashingService otpHashing;
    private final OtpChallengeRepository otpRepository;
    private final OtpSender sender;
    private final PinCredentialService pinCredentials;
    private final AuditWriter audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final SecureRandom secureRandom = new SecureRandom();

    public TransferService(AccountJdbcRepository accounts, TransferJdbcRepository transfers,
                           IdempotencyJdbcRepository idempotency, TransferOtpRepository transferOtp,
                           IdentityJdbcRepository identities, OtpHashingService otpHashing,
                           OtpChallengeRepository otpRepository, OtpSender sender,
                           PinCredentialService pinCredentials, AuditWriter audit, Clock clock,
                           ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.transferOtp = transferOtp;
        this.identities = identities;
        this.otpHashing = otpHashing;
        this.otpRepository = otpRepository;
        this.sender = sender;
        this.pinCredentials = pinCredentials;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public CreateResult create(AuthenticatedActor actor, CreateCommand command, String idempotencyKey) {
        requireCustomer(actor);
        validate(command, idempotencyKey);
        TransferPolicy.parseAmount(command.amount());
        PinCredentialService.VerifyResult pin = pinCredentials.verify(customerId(actor), command.pin());
        if (pin != PinCredentialService.VerifyResult.VALID) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    pin == PinCredentialService.VerifyResult.LOCKED ? "PIN_LOCKED" :
                            pin == PinCredentialService.VerifyResult.NOT_SET ? "PIN_NOT_SET" : "PIN_INVALID",
                    "Transaction PIN is invalid, locked, or not configured.");
        }

        CreateResult result = transactions.execute(status -> createTransaction(actor, command, idempotencyKey));
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

    private CreateResult createTransaction(AuthenticatedActor actor, CreateCommand command, String key) {
        long amount = TransferPolicy.parseAmount(command.amount());
        AccountJdbcRepository.AccountPair pair = accounts.lockPair(command.sourceAccountId(), command.destinationAccountId());
        if (pair == null) throw unavailableAccount();
        var source = pair.source();
        var destination = pair.destination();
        UUID customerId = customerId(actor);
        if (!source.customerId().equals(customerId)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Source account access is forbidden.");
        if (source.id().equals(destination.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Source and destination accounts must differ.");
        validateEligible(source);
        validateEligible(destination);

        String requestHash = hash(command);
        Instant now = clock.instant();
        UUID idempotencyId;
        var existing = idempotency.find(actor.userId(), OPERATION, key);
        if (existing != null) {
            idempotencyId = existing.id();
        } else {
            var claim = idempotency.createOrFind(actor.userId(), OPERATION, key, requestHash, now, now.plus(Duration.ofHours(24)));
            var created = claim.record();
            idempotencyId = created.id();
            existing = claim.created() ? null : created;
        }
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with another request.");
            var prior = transfers.find(existing.resourceId());
            if (prior != null) {
                if ("AWAITING_OTP".equals(prior.status())) {
                    int remaining = Math.max(0, (int) Duration.between(now, prior.expiresAt()).toSeconds());
                    return CreateResult.challengeReplay(prior, existing.id(), remaining);
                }
                return CreateResult.transfer(prior, true);
            }
        }
        if (TransferPolicy.requiresOtp(amount)) {
            var customer = identities.findCustomerById(source.customerId());
            if (customer == null) throw unavailableAccount();
            UUID transferId = transfers.create(source.id(), destination.id(), command.amount(), command.currency(),
                    "AWAITING_OTP", command.memo(), null, idempotencyId, now, now.plus(OTP_TTL));
            UUID challengeId = UUID.randomUUID();
            String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
            Instant expiresAt = now.plus(OTP_TTL);
            transferOtp.create(transferId, challengeId, customer.phone(), otpHashing.encode(code), now, expiresAt);
            return CreateResult.challenge(transferId, idempotencyId, challengeId, customer.phone(), code, expiresAt);
        }

        if (new BigInteger(source.balance()).compareTo(BigInteger.valueOf(amount)) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Available balance is insufficient.");
        }
        UUID transferId = transfers.create(source.id(), destination.id(), command.amount(), command.currency(),
                "COMPLETED", command.memo(), null, idempotencyId, now, null);
        accounts.debit(source.id(), command.amount());
        accounts.credit(destination.id(), command.amount());
        transfers.complete(transferId, now);
        var transfer = transfers.find(transferId);
        idempotency.complete(idempotencyId, 201, transferJson(transfer), transferId);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        publishRiskEvent(transfer, source.id(), now);
        return CreateResult.transfer(transfer, false);
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
        if (transfer == null) return ConfirmOutcome.error(unavailableAccount());
        if ("COMPLETED".equals(transfer.status())) return ConfirmOutcome.replay();
        if (!"AWAITING_OTP".equals(transfer.status())) return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer is not awaiting OTP."));
        Instant now = clock.instant();
        if (transfer.expiresAt() == null || !transfer.expiresAt().isAfter(now)) {
            transfers.expire(transferId, now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "TRANSFER_EXPIRED", "Transfer OTP expired."));
        }
        AccountJdbcRepository.AccountPair pair = accounts.lockPair(transfer.sourceAccountId(), transfer.destinationAccountId());
        if (pair == null || !pair.source().customerId().equals(customerId(actor))) return ConfirmOutcome.error(unavailableAccount());
        var source = pair.source();
        var challenge = transferOtp.lock(transfer.otpChallengeId(), now);
        if (challenge == null || challenge.consumedAt() != null || challenge.invalidatedAt() != null) {
            return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
        }
        if (!challenge.expiresAt().isAfter(now)) {
            transfers.expire(transferId, now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "TRANSFER_EXPIRED", "Transfer OTP expired."));
        }
        if (!otpHashing.matches(otp, challenge.otpHash())) {
            int attempts = challenge.attempts() + 1;
            transferOtp.updateAttempts(challenge.id(), attempts, attempts >= OTP_MAX_ATTEMPTS ? now : null);
            if (attempts >= OTP_MAX_ATTEMPTS) {
                transfers.fail(transferId, "OTP_INVALID", now);
                return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer OTP attempts exceeded."));
            }
            return ConfirmOutcome.error(new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid."));
        }
        if (new BigInteger(source.balance()).compareTo(new BigInteger(transfer.amount())) < 0) {
            transfers.fail(transferId, "INSUFFICIENT_FUNDS", now);
            return ConfirmOutcome.error(new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Available balance is insufficient."));
        }
        transferOtp.consume(challenge.id(), now);
        accounts.debit(source.id(), transfer.amount());
        accounts.credit(pair.destination().id(), transfer.amount());
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

    private UUID customerId(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw unavailableAccount();
        return customer.customerId();
    }

    private void validate(CreateCommand command, String key) {
        if (command == null || command.sourceAccountId() == null || command.destinationAccountId() == null
                || command.currency() == null || !command.currency().equals("VND") || command.pin() == null
                || !command.pin().matches("[0-9]{6}") || key == null || key.length() < 16 || key.length() > 128
                || command.memo() != null && command.memo().length() > 140) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer request is invalid.");
        }
    }

    private void requireCustomer(AuthenticatedActor actor) {
        if (!actor.hasRole("CUSTOMER")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
    }

    private void validateEligible(AccountJdbcRepository.AccountRow account) {
        if (!"ACTIVE".equals(account.status()) || !"VND".equals(account.currency())) throw unavailableAccount();
    }

    private ApiException unavailableAccount() {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_ELIGIBLE", "Account is not available.");
    }

    private String hash(CreateCommand command) {
        try {
            String payload = OPERATION + "|v1|" + command.sourceAccountId() + "|" + command.destinationAccountId()
                    + "|" + command.amount() + "|" + command.currency() + "|" + (command.memo() == null ? "<null>" : command.memo());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
