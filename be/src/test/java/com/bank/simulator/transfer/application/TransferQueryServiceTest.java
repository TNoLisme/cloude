package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferQueryServiceTest {
    @Test
    void historyMergesAllOwnedAccountsWithStableGlobalCursorAndCounterpartyProjection() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID ownAccountA = UUID.randomUUID(), ownAccountB = UUID.randomUUID(), otherAccount = UUID.randomUUID();
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID(), extraId = UUID.randomUUID();
        Instant firstAt = Instant.parse("2026-01-02T00:00:00.123456Z");
        Instant secondAt = Instant.parse("2026-01-01T00:00:00Z");
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of(account(ownAccountA, customerId, "123456789012"),
                account(ownAccountB, customerId, "123456789013")));
        when(accounts.find(otherAccount)).thenReturn(account(otherAccount, UUID.randomUUID(), "987654321098"));
        when(identities.findCustomerById(any())).thenReturn(customer(UUID.randomUUID(), UUID.randomUUID(), "Counterparty"));
        when(transfers.findHistory(eq(List.of(ownAccountA, ownAccountB)), isNull(), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(row(firstId, ownAccountA, otherAccount, "FAILED", firstAt),
                        row(secondId, otherAccount, ownAccountB, "COMPLETED", secondAt),
                        row(extraId, ownAccountB, otherAccount, "COMPLETED", Instant.EPOCH)));
        var service = new TransferQueryService(transfers, accounts, identities,
                new CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

        var page = service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(2, null, null, null, null));

        assertThat(page.items()).extracting(TransferQueryService.TransferView::transferId).containsExactly(firstId, secondId);
        assertThat(page.items().get(0).direction()).isEqualTo("OUTGOING");
        assertThat(page.items().get(1).direction()).isEqualTo("INCOMING");
        assertThat(page.items().get(0).counterpartyAccountMasked()).isEqualTo("••••1098");
        assertThat(page.items().get(0).counterpartyDisplayName()).isEqualTo("Counterparty");
        assertThat(service.decodeCursor(page.nextCursor()).createdAt()).isEqualTo(secondAt);
    }

    @Test
    void rejectsMalformedCursorBeforeQueryingTransfers() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of());
        var service = new TransferQueryService(transfers, accounts, identities);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(20, "bad-cursor", null, null, null)))
                .isInstanceOf(com.bank.simulator.shared.error.ApiException.class);
        verifyNoInteractions(transfers);
    }

    @Test
    void sourceOwnerSeesAwaitingOtpInList() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID ownAccount = UUID.randomUUID(), otherAccount = UUID.randomUUID(), transferId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of(account(ownAccount, customerId, "123456789012")));
        when(accounts.find(otherAccount)).thenReturn(account(otherAccount, UUID.randomUUID(), "987654321098"));
        when(identities.findCustomerById(any())).thenReturn(customer(UUID.randomUUID(), UUID.randomUUID(), "Counterparty"));
        when(transfers.findHistory(eq(List.of(ownAccount)), isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(List.of(row(transferId, ownAccount, otherAccount, "AWAITING_OTP", Instant.EPOCH)));
        var service = new TransferQueryService(transfers, accounts, identities);

        var page = service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(20, null, null, null, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("AWAITING_OTP");
        assertThat(page.items().get(0).direction()).isEqualTo("OUTGOING");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void destinationOwnerCannotSeeNonCompletedTransferDetail() {
        for (String status : List.of("AWAITING_OTP", "FAILED", "EXPIRED")) {
            UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
            UUID ownAccount = UUID.randomUUID(), otherAccount = UUID.randomUUID(), transferId = UUID.randomUUID();
            var accounts = mock(AccountJdbcRepository.class);
            var transfers = mock(TransferJdbcRepository.class);
            var identities = mock(IdentityJdbcRepository.class);
            when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
            when(transfers.find(transferId)).thenReturn(row(transferId, otherAccount, ownAccount, status, Instant.EPOCH));
            when(accounts.find(ownAccount)).thenReturn(account(ownAccount, customerId, "123456789012"));
            when(accounts.find(otherAccount)).thenReturn(account(otherAccount, UUID.randomUUID(), "987654321098"));
            var service = new TransferQueryService(transfers, accounts, identities);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(
                            new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId))
                    .isInstanceOf(com.bank.simulator.shared.error.ApiException.class)
                    .satisfies(error -> {
                        var api = (com.bank.simulator.shared.error.ApiException) error;
                        assertThat(api.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                        assertThat(api.code()).isEqualTo("TRANSFER_NOT_FOUND");
                    });
        }
    }

    @Test
    void destinationOwnerSeesCompletedTransferDetail() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID ownAccount = UUID.randomUUID(), otherAccount = UUID.randomUUID(), transferId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-01-02T00:00:00Z");
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(transfers.find(transferId)).thenReturn(row(transferId, otherAccount, ownAccount, "COMPLETED", completedAt));
        when(accounts.find(ownAccount)).thenReturn(account(ownAccount, customerId, "123456789012"));
        when(accounts.find(otherAccount)).thenReturn(account(otherAccount, UUID.randomUUID(), "987654321098"));
        when(identities.findCustomerById(any())).thenReturn(customer(UUID.randomUUID(), UUID.randomUUID(), "Counterparty"));
        var service = new TransferQueryService(transfers, accounts, identities);

        var view = service.get(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId);

        assertThat(view.direction()).isEqualTo("INCOMING");
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.counterpartyAccountMasked()).isEqualTo("••••1098");
        assertThat(view.counterpartyDisplayName()).isEqualTo("Counterparty");
    }

    @Test
    void nextCursorEncodedFromLastReturnedItemWhenRepositoryReturnsExtraRow() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID ownAccount = UUID.randomUUID(), otherAccount = UUID.randomUUID();
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID(), extraId = UUID.randomUUID();
        Instant sameCreatedAt = Instant.parse("2026-01-02T00:00:00Z");
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        when(identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(accounts.findByCustomer(customerId)).thenReturn(List.of(account(ownAccount, customerId, "123456789012")));
        when(accounts.find(otherAccount)).thenReturn(account(otherAccount, UUID.randomUUID(), "987654321098"));
        when(identities.findCustomerById(any())).thenReturn(customer(UUID.randomUUID(), UUID.randomUUID(), "Counterparty"));
        when(transfers.findHistory(eq(List.of(ownAccount)), isNull(), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(row(firstId, ownAccount, otherAccount, "COMPLETED", sameCreatedAt),
                        row(secondId, ownAccount, otherAccount, "COMPLETED", sameCreatedAt),
                        row(extraId, ownAccount, otherAccount, "COMPLETED", sameCreatedAt)));
        var service = new TransferQueryService(transfers, accounts, identities,
                new CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

        var page = service.list(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferQueryService.HistoryQuery(2, null, null, null, null));

        assertThat(page.items()).extracting(TransferQueryService.TransferView::transferId)
                .containsExactly(firstId, secondId);
        var cursor = service.decodeCursor(page.nextCursor());
        assertThat(cursor.createdAt()).isEqualTo(sameCreatedAt);
        assertThat(cursor.id()).isEqualTo(secondId);
    }

    private IdentityJdbcRepository.CustomerRecord customer(UUID customerId, UUID userId) {
        return customer(customerId, userId, "Owner");
    }

    private IdentityJdbcRepository.CustomerRecord customer(UUID customerId, UUID userId, String name) {
        return new IdentityJdbcRepository.CustomerRecord(customerId, userId, name, null, Instant.EPOCH,
                "0912345678", "owner@example.test", true);
    }

    private AccountJdbcRepository.AccountRow account(UUID id, UUID customerId, String number) {
        return new AccountJdbcRepository.AccountRow(id, customerId, number, "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH);
    }

    private TransferJdbcRepository.TransferRow row(UUID id, UUID source, UUID destination, String status, Instant createdAt) {
        return new TransferJdbcRepository.TransferRow(id, source, destination, "2000", "VND", status,
                null, null, null, createdAt, null, "COMPLETED".equals(status) ? createdAt : null);
    }
}
