package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.account.infrastructure.persistence.SeedRecordJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
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
public class AccountOperatorService {
    private static final String OPERATION = "seedAccountBalance";

    private final AccountJdbcRepository accounts;
    private final SeedRecordJdbcRepository seeds;
    private final IdempotencyJdbcRepository idempotency;
    private final AuditWriter audit;
    private final Clock clock;

    public AccountOperatorService(AccountJdbcRepository accounts, SeedRecordJdbcRepository seeds,
                                  IdempotencyJdbcRepository idempotency, AuditWriter audit, Clock clock) {
        this.accounts = accounts;
        this.seeds = seeds;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public SeedBalanceResult seed(AuthenticatedActor actor, UUID accountId, SeedBalanceCommand command,
                                  String idempotencyKey) {
        requireOperator(actor);
        validate(command, idempotencyKey);
        String hash = hash(accountId, command);
        Instant now = clock.instant();
        IdempotencyJdbcRepository.IdempotencyClaim claim = idempotency.createOrFind(actor.userId(), OPERATION,
                idempotencyKey, hash, now, now.plus(Duration.ofHours(24)));
        IdempotencyJdbcRepository.IdempotencyRecord existing = claim.created() ? null : claim.record();
        if (existing != null) {
            if (!existing.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with another request.");
            }
            if (existing.responseBody() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request is still in progress.");
            }
            return SeedBalanceResult.fromJson(existing.responseBody(), true);
        }
        UUID idempotencyId = claim.record().id();
        AccountJdbcRepository.AccountRow account = accounts.lock(accountId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account is not available.");
        }
        if (!"ACTIVE".equals(account.status()) || !"VND".equals(account.currency())) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE", "Account cannot receive seed funds.");
        }
        SeedRecordJdbcRepository.SeedRecord seed = seeds.insert(accountId, command.amount(), command.currency(),
                actor.userId(), command.reference(), idempotencyId, now);
        accounts.credit(accountId, command.amount());
        String balanceAfter = new BigInteger(account.balance()).add(new BigInteger(command.amount())).toString();
        SeedBalanceResult result = new SeedBalanceResult(seed.seedTransactionId(), accountId, command.amount(),
                command.currency(), balanceAfter, seed.createdAt(), false);
        idempotency.complete(idempotencyId, 201, result.toJson(), seed.seedTransactionId());
        audit.record(actor.userId(), firstRole(actor), "ACCOUNT_BALANCE_SEEDED", "ACCOUNT", accountId,
                "SUCCESS", null, "Account balance seeded.");
        return result;
    }

    private void requireOperator(AuthenticatedActor actor) {
        if (!actor.hasRole("OPERATOR") && !actor.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
    }

    private void validate(SeedBalanceCommand command, String idempotencyKey) {
        if (command == null || command.amount() == null || command.currency() == null || command.reference() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Seed request is invalid.");
        }
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency key is invalid.");
        }
        if (!"VND".equals(command.currency()) || command.amount().isBlank()
                || !command.amount().matches("(?:[1-9][0-9]{0,7}|100000000)")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Seed amount is invalid.");
        }
        BigInteger amount = new BigInteger(command.amount());
        if (amount.signum() <= 0 || amount.compareTo(BigInteger.valueOf(100_000_000L)) > 0
                || command.reference().isBlank() || command.reference().length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Seed request is invalid.");
        }
    }

    private String hash(UUID accountId, SeedBalanceCommand command) {
        try {
            byte[] value = (OPERATION + "|v1|" + accountId + "|" + command.amount() + "|"
                    + command.currency() + "|" + command.reference()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String firstRole(AuthenticatedActor actor) {
        return actor.roles().stream().sorted().findFirst().orElse(null);
    }

    public record SeedBalanceResult(UUID seedTransactionId, UUID accountId, String amount, String currency,
                                    String balanceAfter, Instant createdAt, boolean replayed) {
        static SeedBalanceResult fromJson(String responseBody, boolean replayed) {
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
                return new SeedBalanceResult(UUID.fromString(json.get("seedTransactionId").asText()),
                        UUID.fromString(json.get("accountId").asText()), json.get("amount").asText(),
                        json.get("currency").asText(), json.get("balanceAfter").asText(),
                        Instant.parse(json.get("createdAt").asText()), replayed);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Stored seed response is invalid", exception);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Stored seed response is invalid", exception);
            }
        }

        String toJson() {
            return "{\"seedTransactionId\":\"" + seedTransactionId + "\",\"accountId\":\"" + accountId
                    + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency
                    + "\",\"balanceAfter\":\"" + balanceAfter + "\",\"createdAt\":\"" + createdAt + "\"}";
        }
    }
}
