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
        when(idempotency.find(actorId, "seedAccountBalance", "abcdefghijklmnop"))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyRecord(
                        UUID.randomUUID(), "different-hash", 201, "{}", UUID.randomUUID(), Instant.MAX));

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
    void blockedAccountCannotReceiveSeed() {
        UUID accountId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = service(accounts, seeds, idempotency, audit);
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR"));
        when(idempotency.find(any(), eq("seedAccountBalance"), anyString())).thenReturn(null);
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
