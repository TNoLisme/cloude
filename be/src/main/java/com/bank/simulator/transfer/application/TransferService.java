package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TransferService {
    private static final String OPERATION = "createTransfer";
    private static final Duration OTP_TTL = Duration.ofSeconds(120);

    private final AccountJdbcRepository accounts;
    private final TransferJdbcRepository transfers;
    private final IdempotencyJdbcRepository idempotency;
    private final com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository identities;
    private final com.bank.simulator.identity.infrastructure.otp.OtpHashingService otpHashing;
    private final com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository otpRepository;
    private final OtpSender sender;
    private final com.bank.simulator.identity.infrastructure.security.PinHashingService pinHashing;
    private final AuditWriter audit;
    private final Clock clock;

    public TransferService(AccountJdbcRepository accounts, TransferJdbcRepository transfers,
                           IdempotencyJdbcRepository idempotency,
                           com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository identities,
                           com.bank.simulator.identity.infrastructure.otp.OtpHashingService otpHashing,
                           com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository otpRepository,
                           OtpSender sender, PinHashingService pinHashing,
                           AuditWriter audit, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.identities = identities;
        this.otpHashing = otpHashing;
        this.otpRepository = otpRepository;
        this.sender = sender;
        this.pinHashing = pinHashing;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CreateResult create(AuthenticatedActor actor, CreateCommand command, String idempotencyKey) {
        requireCustomer(actor);
        long amount = TransferPolicy.parseAmount(command.amount());
        validate(command, idempotencyKey);
        var source = accounts.lock(command.sourceAccountId());
        var destination = accounts.lock(command.destinationAccountId());
        if (source == null || destination == null) throw unavailableAccount();
        if (!source.customerId().equals(customerId(actor))) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Source account access is forbidden.");
        if (source.id().equals(destination.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Source and destination accounts must differ.");
        validateEligible(source);
        validateEligible(destination);
        var pin = identities.lockPin(source.customerId());
        if (pin == null || pin.pinHash() == null) throw new ApiException(HttpStatus.FORBIDDEN, "PIN_NOT_SET", "Transaction PIN is not configured.");
        if (!pinHashing.matches(command.pin(), pin.pinHash())) throw new ApiException(HttpStatus.FORBIDDEN, "PIN_INVALID", "Transaction PIN is invalid.");

        String hash = hash(command);
        Instant now = clock.instant();
        var existing = idempotency.find(actor.userId(), OPERATION, idempotencyKey);
        if (existing != null) {
            if (!existing.requestHash().equals(hash)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with another request.");
            var prior = transfers.find(existing.resourceId());
            if (prior != null) return CreateResult.transfer(prior, true);
        }
        UUID idempotencyId = existing == null ? idempotency.create(actor.userId(), OPERATION, idempotencyKey,
                hash, now, now.plus(Duration.ofHours(24))) : existing.id();

        if (TransferPolicy.requiresOtp(amount)) {
            var customer = identities.findCustomerById(source.customerId());
            if (customer == null) throw unavailableAccount();
            UUID transferId = transfers.create(source.id(), destination.id(), command.amount(), command.currency(),
                    "AWAITING_OTP", command.memo(), null, idempotencyId, now, now.plus(OTP_TTL));
            try {
                OtpChallengeService.OtpIssueResult challenge = new OtpChallengeService(otpRepository, otpHashing, sender, clock)
                        .issue(customer.phone(), "SMS", "TRANSFER_STEP_UP");
                audit.record(actor.userId(), "CUSTOMER", "TRANSFER_OTP_REQUESTED", "TRANSFER", transferId,
                        "SUCCESS", null, "Transfer step-up OTP requested.");
                return CreateResult.challenge(transferId, challenge.expiresAt(), (int) OTP_TTL.toSeconds(), false);
            } catch (RuntimeException exception) {
                transfers.fail(transferId, "OTP_DISPATCH_FAILED", now);
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Transfer OTP delivery failed.");
            }
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
        String json = transferJson(transfer);
        idempotency.complete(idempotencyId, 201, json, transferId);
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        return CreateResult.transfer(transfer, false);
    }

    @Transactional
    public boolean confirm(AuthenticatedActor actor, UUID transferId, String otp) {
        requireCustomer(actor);
        var transfer = transfers.find(transferId);
        if (transfer == null) throw unavailableAccount();
        if ("COMPLETED".equals(transfer.status())) return true;
        if (!"AWAITING_OTP".equals(transfer.status())) throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer is not awaiting OTP.");
        if (transfer.expiresAt() == null || !transfer.expiresAt().isAfter(clock.instant())) {
            transfers.fail(transferId, "EXPIRED", clock.instant());
            throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "Transfer OTP expired.");
        }
        var source = accounts.lock(transfer.sourceAccountId());
        var destination = accounts.lock(transfer.destinationAccountId());
        if (source == null || destination == null || !source.customerId().equals(customerId(actor))) throw unavailableAccount();
        var challenge = otpRepository.lockChallenge(transfer.otpChallengeId(), source.customerId().toString(), "SMS", "TRANSFER_STEP_UP", clock.instant());
        if (challenge == null || !otpHashing.matches(otp, challenge.otpHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid.");
        }
        if (new BigInteger(source.balance()).compareTo(new BigInteger(transfer.amount())) < 0) {
            transfers.fail(transferId, "INSUFFICIENT_FUNDS", clock.instant());
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Available balance is insufficient.");
        }
        otpRepository.consume(challenge.id(), clock.instant());
        accounts.debit(source.id(), transfer.amount());
        accounts.credit(destination.id(), transfer.amount());
        transfers.complete(transferId, clock.instant());
        audit.record(actor.userId(), "CUSTOMER", "TRANSFER_COMPLETED", "TRANSFER", transferId,
                "SUCCESS", null, "Internal transfer completed.");
        return false;
    }

    private UUID customerId(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw unavailableAccount();
        return customer.customerId();
    }

    private void validate(CreateCommand command, String key) {
        if (command == null || command.sourceAccountId() == null || command.destinationAccountId() == null
                || command.currency() == null || !command.currency().equals("VND")
                || command.pin() == null || !command.pin().matches("[0-9]{6}")
                || key == null || key.length() < 16 || key.length() > 128
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

    public record CreateResult(boolean challengeRequired, UUID transferId, String status, String message,
                               Instant expiresAt, int expiresInSeconds, String failureCode, boolean replayed) {
        static CreateResult challenge(UUID id, Instant expiresAt, int ttl, boolean replayed) {
            return new CreateResult(true, id, "AWAITING_OTP", "Transfer exceeds 5,000,000 VND. OTP sent to registered phone number.", expiresAt, ttl, null, replayed);
        }
        static CreateResult transfer(TransferJdbcRepository.TransferRow row, boolean replayed) {
            return new CreateResult(false, row.id(), row.status(), null, row.expiresAt(), 0, row.failureCode(), replayed);
        }
    }
}
