package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfirmTransferOtpServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void validOtpCompletesTransferAndPublishesRiskEvent() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transfers.find(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));

        var result = d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456");

        assertThat(result.replayed()).isFalse();
        verify(d.accounts).debit(sourceId, new BigDecimal("6000000"));
        verify(d.failureInjector).afterDebitBeforeCredit();
        verify(d.accounts).credit(destinationId, new BigDecimal("6000000"));
        verify(d.transfers).complete(transferId, NOW);
        verify(d.audit).record(eq(userId), eq("CUSTOMER"), eq("TRANSFER_COMPLETED"), eq("TRANSFER"), eq(transferId),
                eq("SUCCESS"), isNull(), anyString());
        verify(d.events).publishEvent(any(TransferCommittedEvent.class));
    }

    @Test
    void locksTransferThenChallengeThenAccountsAscending() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transfers.find(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));

        d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456");

        InOrder order = inOrder(d.transfers, d.identities, d.accounts);
        order.verify(d.transfers).lock(transferId);
        order.verify(d.accounts).ownerCustomerId(sourceId);
        order.verify(d.identities).consumeOtp(challengeId, "123456");
        order.verify(d.accounts).lockAccountsAscending(sourceId, destinationId);
        order.verify(d.transfers).complete(transferId, NOW);
    }

    @Test
    void wrongOtpAttemptsOneToFourReturn400WithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "000000")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.INVALID, 1, 5));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ApiException) error).code()).isEqualTo("OTP_INVALID");
                });
        verify(d.accounts, never()).lockAccountsAscending(any(), any());
        verify(d.accounts, never()).debit(any(), any());
        verify(d.transfers, never()).fail(any(), anyString(), any());
        verify(d.transfers, never()).complete(any(), any());
    }

    @Test
    void fifthWrongOtpFailsTransferAndKeepsStateConflictOnLaterAttempts() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "000000")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.ATTEMPTS_EXCEEDED, 5, 5));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT");
                });
        verify(d.transfers).fail(transferId, "OTP_ATTEMPTS_EXCEEDED", NOW);
        verify(d.accounts, never()).debit(any(), any());

        when(d.transfers.lock(transferId)).thenReturn(failed(transferId, sourceId, destinationId, challengeId));
        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT"));
        verify(d.identities, times(1)).consumeOtp(any(), any());
    }

    @Test
    void invalidatedOrAlreadyUsedChallengeCannotCompleteTransfer() {
        for (IdentityModuleApi.OtpConsumeResult.Outcome outcome : new IdentityModuleApi.OtpConsumeResult.Outcome[]{
                IdentityModuleApi.OtpConsumeResult.Outcome.INVALIDATED,
                IdentityModuleApi.OtpConsumeResult.Outcome.ALREADY_USED,
                IdentityModuleApi.OtpConsumeResult.Outcome.NOT_FOUND}) {
            var d = dependencies();
            UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
            UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
            when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
            when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
            when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
            when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(
                    IdentityModuleApi.OtpConsumeResult.of(outcome, 0, 5));

            assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(((ApiException) error).code()).isEqualTo("OTP_INVALID");
                    });
            verify(d.accounts, never()).debit(any(), any());
        }
    }

    @Test
    void expiredChallengeExpiresTransferWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.EXPIRED, 0, 5));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        verify(d.transfers).expire(transferId, NOW);
        verify(d.accounts, never()).lockAccountsAscending(any(), any());
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void transferPastExpiresAtExpiresBeforeConsumingOtp() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(new TransferJdbcRepository.TransferRow(transferId, sourceId,
                destinationId, "6000000", "VND", "AWAITING_OTP", null, null, challengeId, NOW,
                NOW.minusNanos(1), null));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        verify(d.transfers).expire(transferId, NOW);
        verify(d.identities, never()).consumeOtp(any(), any());
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void confirmAtExactExpiryInstantStillCompletes() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(new TransferJdbcRepository.TransferRow(transferId, sourceId,
                destinationId, "6000000", "VND", "AWAITING_OTP", null, null, challengeId, NOW, NOW, null));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transfers.find(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));

        var result = d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456");

        assertThat(result.replayed()).isFalse();
        verify(d.transfers, never()).expire(any(), any());
        verify(d.transfers).complete(transferId, NOW);
    }

    @Test
    void physicallyExpiredTransferReturns409WithoutMutation() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(new TransferJdbcRepository.TransferRow(transferId, sourceId,
                destinationId, "6000000", "VND", "EXPIRED", null, null, UUID.randomUUID(), NOW, null, null));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        verify(d.transfers, never()).expire(any(), any());
        verify(d.identities, never()).consumeOtp(any(), any());
    }

    @Test
    void failedTransferReturns409StateConflictWithoutOtpInteraction() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(failed(transferId, sourceId, destinationId, challengeId));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT");
                });
        verify(d.identities, never()).consumeOtp(any(), any());
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void duplicateConfirmReplaysWithoutMoneyMutation() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        var completed = new TransferJdbcRepository.TransferRow(transferId, sourceId, UUID.randomUUID(), "6000000", "VND",
                "COMPLETED", null, null, null, NOW, null, NOW);
        when(d.transfers.lock(transferId)).thenReturn(completed);

        assertThat(d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456").replayed())
                .isTrue();
        verify(d.accounts, never()).debit(any(), any());
        verify(d.identities, never()).consumeOtp(any(), any());
        verify(d.transfers, never()).complete(any(), any());
    }

    @Test
    void actorThatDoesNotOwnSourceAccountReceives404() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(UUID.randomUUID());
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, UUID.randomUUID(), challengeId));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_NOT_FOUND");
                });
        verify(d.identities, never()).consumeOtp(any(), any());
    }

    @Test
    void missingTransferReturns404() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), transferId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(null);

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_NOT_FOUND"));
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void blockedSourceFailsTransferAndInvalidatesOtpWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "BLOCKED", "10000000"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0")));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", NOW);
        verify(d.identities).invalidateOtp(challengeId);
        verify(d.accounts, never()).debit(any(), any());
        verify(d.accounts, never()).credit(any(), any());
    }

    @Test
    void blockedDestinationFailsTransferAndInvalidatesOtpWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000"),
                account(destinationId, UUID.randomUUID(), "BLOCKED", "0")));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE"));
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", NOW);
        verify(d.identities).invalidateOtp(challengeId);
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void currencyMismatchFailsTransferWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0", "USD")));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE"));
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", NOW);
        verify(d.identities).invalidateOtp(challengeId);
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void insufficientBalanceFailsTransferWithoutCredit() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.ownerCustomerId(sourceId)).thenReturn(customerId);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId));
        when(d.identities.consumeOtp(challengeId, "123456")).thenReturn(IdentityModuleApi.OtpConsumeResult.of(
                IdentityModuleApi.OtpConsumeResult.Outcome.VALID, 0, 5));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(new AccountModuleApi.LockedAccountPair(
                account(sourceId, customerId, "ACTIVE", "100"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0")));

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        verify(d.transfers).fail(transferId, "INSUFFICIENT_FUNDS", NOW);
        verify(d.accounts, never()).debit(any(), any());
        verify(d.accounts, never()).credit(any(), any());
    }

    private Dependencies dependencies() {
        var accounts = mock(AccountModuleApi.class);
        var transfers = mock(TransferJdbcRepository.class);
        var identities = mock(IdentityModuleApi.class);
        var audit = mock(AuditWriter.class);
        var events = mock(ApplicationEventPublisher.class);
        var failureInjector = mock(TransferFailureInjector.class);
        var service = new ConfirmTransferOtpService(accounts, transfers, identities, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), events, new InlineTransactionManager(), failureInjector);
        return new Dependencies(service, accounts, transfers, identities, audit, events, failureInjector);
    }

    private TransferJdbcRepository.TransferRow awaiting(UUID id, UUID source, UUID destination, UUID challenge) {
        return new TransferJdbcRepository.TransferRow(id, source, destination, "6000000", "VND", "AWAITING_OTP",
                null, null, challenge, NOW, NOW.plusSeconds(120), null);
    }

    private TransferJdbcRepository.TransferRow failed(UUID id, UUID source, UUID destination, UUID challenge) {
        return new TransferJdbcRepository.TransferRow(id, source, destination, "6000000", "VND", "FAILED",
                "OTP_ATTEMPTS_EXCEEDED", null, challenge, NOW, null, null);
    }

    private IdentityModuleApi.CustomerView customer(UUID customerId, UUID userId) {
        return new IdentityModuleApi.CustomerView(customerId, userId, "Customer", "0912345678", true);
    }

    private AccountModuleApi.LockedAccount account(UUID id, UUID customerId, String status, String balance) {
        return account(id, customerId, status, balance, "VND");
    }

    private AccountModuleApi.LockedAccount account(UUID id, UUID customerId, String status, String balance, String currency) {
        return new AccountModuleApi.LockedAccount(id, customerId, status, balance, currency);
    }

    private record Dependencies(ConfirmTransferOtpService service, AccountModuleApi accounts,
                                TransferJdbcRepository transfers, IdentityModuleApi identities, AuditWriter audit,
                                ApplicationEventPublisher events, TransferFailureInjector failureInjector) {}

    private static final class InlineTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return mock(TransactionStatus.class); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
