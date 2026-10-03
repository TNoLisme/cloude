package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountStatusServiceTest {
    @Test
    void repeatedBlockDoesNotWriteDuplicateAudit() {
        UUID accountId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = new AccountStatusService(accounts, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR"));
        var account = new AccountJdbcRepository.AccountRow(accountId, UUID.randomUUID(), "123456789012",
                "CHECKING", "BLOCKED", "0", "VND", Instant.EPOCH);
        when(accounts.lock(accountId)).thenReturn(account);

        var result = service.change(actor, accountId, "BLOCKED", "same reason");

        assertThat(result.status()).isEqualTo("BLOCKED");
        verify(accounts, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void closedAccountCannotBeUnblocked() {
        UUID accountId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var service = new AccountStatusService(accounts, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("ADMIN"));
        when(accounts.lock(accountId)).thenReturn(new AccountJdbcRepository.AccountRow(accountId, UUID.randomUUID(),
                "123456789012", "CHECKING", "CLOSED", "0", "VND", Instant.EPOCH));

        assertThatThrownBy(() -> service.change(actor, accountId, "ACTIVE", "restore"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var exception = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });
        verifyNoInteractions(audit);
    }
}
