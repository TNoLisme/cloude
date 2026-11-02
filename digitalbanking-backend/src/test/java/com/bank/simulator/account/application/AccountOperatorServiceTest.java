package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.account.infrastructure.persistence.SeedRecordJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountOperatorServiceTest {
    private AccountOperatorService service(AccountJdbcRepository accounts, SeedRecordJdbcRepository seeds,
                                           IdempotencyJdbcRepository idempotency, AuditWriter audit) {
        return new AccountOperatorService(accounts, seeds, idempotency, audit,
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void customerCannotSeedBalance() {
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = service(accounts, seeds, idempotency, audit);
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));

        assertThatThrownBy(() -> service.seed(actor, UUID.randomUUID(),
                new SeedBalanceCommand("1000", "VND", "demo"), "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var exception = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("FORBIDDEN");
                });
        verifyNoInteractions(accounts, seeds, idempotency, audit);
    }

    @Test
    void seedReplayReturnsConflictWhenPayloadChanges() {
        UUID actorId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = service(accounts, seeds, idempotency, audit);
        var actor = new AuthenticatedActor(actorId, Set.of("OPERATOR"));
        var existingId = UUID.randomUUID();
        when(idempotency.createOrFind(eq(actorId), eq("seedAccountBalance"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(existingId, "different-hash", 201, "{}", UUID.randomUUID(), Instant.MAX), false));

        assertThatThrownBy(() -> service.seed(actor, UUID.randomUUID(),
                new SeedBalanceCommand("1000", "VND", "demo"), "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var exception = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        verifyNoInteractions(accounts, seeds, audit);
    }

    @Test
    void seedResponseUsesInsertedLedgerIdAndReplayRestoresOriginalResult() {
        UUID actorId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID idempotencyId = UUID.randomUUID();
        String key = "abcdefghijklmnop";
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = service(accounts, seeds, idempotency, audit);
        var actor = new AuthenticatedActor(actorId, Set.of("OPERATOR"));
        when(idempotency.createOrFind(eq(actorId), eq("seedAccountBalance"), eq(key), anyString(), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(idempotencyId, "hash", null, null, null,
                                now.plusSeconds(3600)), true));
        when(accounts.lock(accountId)).thenReturn(new AccountJdbcRepository.AccountRow(accountId, UUID.randomUUID(),
                "123456789012", "CHECKING", "ACTIVE", "10000", "VND", Instant.EPOCH));
        when(seeds.insert(eq(accountId), eq("2000"), eq("VND"), eq(actorId), eq("demo"), any(), any()))
                .thenReturn(new SeedRecordJdbcRepository.SeedRecord(ledgerId, accountId, "2000", "VND", now));

        var created = service.seed(actor, accountId, new SeedBalanceCommand("2000", "VND", "demo"), key);

        org.assertj.core.api.Assertions.assertThat(created.seedTransactionId()).isEqualTo(ledgerId);
        org.assertj.core.api.Assertions.assertThat(created.balanceAfter()).isEqualTo("12000");
        verify(idempotency).complete(idempotencyId, 201, created.toJson(), ledgerId);
        clearInvocations(audit);

        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("seedAccountBalance|v1|" + accountId + "|2000|VND|demo")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        when(idempotency.createOrFind(eq(actorId), eq("seedAccountBalance"), eq(key), eq(hash), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(idempotencyId, hash, 201,
                                created.toJson(), ledgerId, now.plusSeconds(3600)), false));
        var replay = service.seed(actor, accountId, new SeedBalanceCommand("2000", "VND", "demo"), key);
        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(new AccountOperatorService.SeedBalanceResult(
                ledgerId, accountId, "2000", "VND", "12000", now, true));
        verifyNoInteractions(audit);
    }

    @Test
    void blockedAccountCannotReceiveSeed() {
        UUID accountId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = service(accounts, seeds, idempotency, audit);
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR"));
        when(idempotency.createOrFind(any(), eq("seedAccountBalance"), anyString(), anyString(), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), "hash", null, null, null,
                                Instant.MAX), true));
        when(accounts.lock(accountId)).thenReturn(new AccountJdbcRepository.AccountRow(accountId, UUID.randomUUID(),
                "123456789012", "CHECKING", "BLOCKED", "0", "VND", Instant.EPOCH));

        assertThatThrownBy(() -> service.seed(actor, accountId,
                new SeedBalanceCommand("1000", "VND", "demo"), "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var exception = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });
        verify(accounts, never()).credit(any(), anyString());
        verifyNoInteractions(audit);
    }
}
