package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.account.infrastructure.persistence.SeedRecordJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountOperatorServiceTestAdditional {
    @Test
    void seedCreatesExpiredAwareAtomicClaim() {
        var accounts = mock(AccountJdbcRepository.class);
        var seeds = mock(SeedRecordJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var service = new AccountOperatorService(accounts, seeds, idempotency, mock(AuditWriter.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID actorId = UUID.randomUUID(), accountId = UUID.randomUUID(), claimId = UUID.randomUUID();
        when(idempotency.createOrFind(eq(actorId), eq("seedAccountBalance"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(claimId, "hash", null, null, null,
                                Instant.EPOCH.plusSeconds(3600)), true));
        when(accounts.lock(accountId)).thenReturn(new AccountJdbcRepository.AccountRow(accountId, UUID.randomUUID(),
                "123456789012", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH));
        when(seeds.insert(eq(accountId), eq("2000"), eq("VND"), eq(actorId), eq("demo"), eq(claimId), any()))
                .thenReturn(new SeedRecordJdbcRepository.SeedRecord(UUID.randomUUID(), accountId, "2000", "VND", Instant.EPOCH));

        service.seed(new AuthenticatedActor(actorId, Set.of("OPERATOR")), accountId,
                new SeedBalanceCommand("2000", "VND", "demo"), "abcdefghijklmnop");

        verify(idempotency).createOrFind(eq(actorId), eq("seedAccountBalance"), eq("abcdefghijklmnop"), anyString(), any(), any());
    }
}
