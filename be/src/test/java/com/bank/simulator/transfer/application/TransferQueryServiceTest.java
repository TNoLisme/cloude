package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TransferQueryServiceTest {
    @Test
    void historyIncludesCustomerOutgoingAndCompletedIncomingOnly() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID ownAccountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        UUID outgoingId = UUID.randomUUID();
        UUID incomingId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                customerId, userId, "Owner", null, Instant.EPOCH, "0912345678", "owner@example.test", true));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of(
                new AccountJdbcRepository.AccountRow(ownAccountId, customerId, "123456789012", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH)));
        when(transfers.findHistory(eq(ownAccountId), isNull(), any(), any(), isNull(), isNull(), anyInt())).thenReturn(List.of(
                new TransferJdbcRepository.TransferRow(outgoingId, ownAccountId, otherAccountId, "2000", "VND", "FAILED", "OTP_INVALID", null, null, Instant.parse("2026-01-02T00:00:00Z"), null, null),
                new TransferJdbcRepository.TransferRow(incomingId, otherAccountId, ownAccountId, "2500", "VND", "COMPLETED", null, null, null, Instant.EPOCH, null, Instant.EPOCH)));
        var service = new TransferQueryService(transfers, accounts, identities);

        var items = service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")));

        assertThat(items).extracting(TransferQueryService.TransferView::transferId)
                .containsExactly(outgoingId, incomingId);
        verify(transfers).findHistory(eq(ownAccountId), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void historySupportsStatusFilterAndStablePageShape() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID ownAccountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-02T00:00:00Z");
        UUID transferId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                customerId, userId, "Owner", null, Instant.EPOCH, "0912345678", "owner@example.test", true));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of(
                new AccountJdbcRepository.AccountRow(ownAccountId, customerId, "123456789012", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH)));
        when(transfers.findHistory(eq(ownAccountId), eq("FAILED"), any(), any(), isNull(), isNull(), anyInt())).thenReturn(List.of(
                new TransferJdbcRepository.TransferRow(transferId, ownAccountId, otherAccountId, "2000", "VND", "FAILED", "OTP_INVALID", null, null, createdAt, null, null)));
        var service = new TransferQueryService(transfers, accounts, identities);

        var page = service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(1, null, "FAILED", null, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items().getFirst().direction()).isEqualTo("OUTGOING");
    }

    @Test
    void malformedCursorReturnsValidationError() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                customerId, userId, "Owner", null, Instant.EPOCH, "0912345678", "owner@example.test", true));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of());
        var service = new TransferQueryService(transfers, accounts, identities);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(
                new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(20, "bad-cursor", null, null, null)))
                .isInstanceOf(com.bank.simulator.shared.error.ApiException.class)
                .satisfies(error -> assertThat(((com.bank.simulator.shared.error.ApiException) error).status())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }
}
