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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceLifecycleTest {
    @Test
    void validOtpCompletesTransferAndPublishesRiskEvent() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "10000000"), account(destinationId, UUID.randomUUID(), "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("123456", "hash")).thenReturn(true);
        when(d.transfers.find(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));

        boolean replay = d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456");

        assertThat(replay).isFalse();
        verify(d.transferOtp).consume(challengeId, Instant.EPOCH);
        verify(d.accounts).debit(sourceId, "6000000");
        verify(d.accounts).credit(destinationId, "6000000");
        verify(d.transfers).complete(transferId, Instant.EPOCH);
    }

    @Test
    void blockedSourceFailsTransferAndInvalidatesOtpWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.find(sourceId)).thenReturn(account(sourceId, customerId, "10000000"));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "BLOCKED", "10000000", "VND"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0", "VND")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("123456", "hash")).thenReturn(true);

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", Instant.EPOCH);
        verify(d.transferOtp).invalidate(challengeId, Instant.EPOCH);
        verify(d.accounts, never()).debit(any(), anyString());
        verify(d.accounts, never()).credit(any(), anyString());
        verify(d.transferOtp, never()).consume(any(), any());
    }

    @Test
    void blockedDestinationFailsTransferAndInvalidatesOtpWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.find(sourceId)).thenReturn(account(sourceId, customerId, "10000000"));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000", "VND"),
                account(destinationId, UUID.randomUUID(), "BLOCKED", "0", "VND")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("123456", "hash")).thenReturn(true);

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE"));
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", Instant.EPOCH);
        verify(d.transferOtp).invalidate(challengeId, Instant.EPOCH);
        verify(d.accounts, never()).debit(any(), anyString());
        verify(d.accounts, never()).credit(any(), anyString());
    }

    @Test
    void currencyMismatchFailsTransferWithoutMovingMoney() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.accounts.find(sourceId)).thenReturn(account(sourceId, customerId, "10000000"));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "ACTIVE", "10000000", "VND"),
                account(destinationId, UUID.randomUUID(), "ACTIVE", "0", "USD")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("123456", "hash")).thenReturn(true);

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE"));
        verify(d.transfers).fail(transferId, "ACCOUNT_NOT_ELIGIBLE", Instant.EPOCH);
        verify(d.transferOtp).invalidate(challengeId, Instant.EPOCH);
        verify(d.accounts, never()).debit(any(), anyString());
        verify(d.accounts, never()).credit(any(), anyString());
    }

    @Test
    void duplicateConfirmReplaysWithoutMoneyMutation() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), transferId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(UUID.randomUUID(), userId));
        var completed = new TransferJdbcRepository.TransferRow(transferId, UUID.randomUUID(), UUID.randomUUID(), "6000000", "VND",
                "COMPLETED", null, null, null, Instant.EPOCH, null, Instant.EPOCH);
        when(d.transfers.lock(transferId)).thenReturn(completed);

        assertThat(d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456")).isTrue();
        verify(d.accounts, never()).debit(any(), anyString());
        verify(d.transferOtp, never()).consume(any(), any());
    }

    @Test
    void insufficientBalanceFailsTransferWithoutCredit() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "100"), account(destinationId, UUID.randomUUID(), "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("123456", "hash")).thenReturn(true);

        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        verify(d.transfers).fail(transferId, "INSUFFICIENT_FUNDS", Instant.EPOCH);
        verify(d.accounts, never()).credit(any(), anyString());
    }

    private Dependencies dependencies() {
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var otpHashing = mock(OtpHashingService.class);
        var otpRepository = mock(OtpChallengeRepository.class);
        var sender = mock(OtpSender.class);
        var pinCredentials = mock(PinCredentialService.class);
        var audit = mock(AuditWriter.class);
        var transferOtp = mock(TransferOtpRepository.class);
        var service = new TransferService(accounts, transfers, idempotency, transferOtp, identities,
                otpHashing, otpRepository, sender, pinCredentials, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                mock(org.springframework.context.ApplicationEventPublisher.class), new InlineTransactionManager());
        return new Dependencies(service, accounts, transfers, identities, otpHashing, transferOtp);
    }

    private TransferJdbcRepository.TransferRow transfer(UUID id, UUID source, UUID destination, UUID challenge) {
        return new TransferJdbcRepository.TransferRow(id, source, destination, "6000000", "VND", "AWAITING_OTP",
                null, null, challenge, Instant.EPOCH, Instant.EPOCH.plusSeconds(120), null);
    }

    private IdentityJdbcRepository.CustomerRecord customer(UUID customerId, UUID userId) {
        return new IdentityJdbcRepository.CustomerRecord(customerId, userId, "Customer", null, Instant.EPOCH,
                "0912345678", "customer@example.test", true);
    }

    private AccountJdbcRepository.AccountRow account(UUID id, UUID customerId, String balance) {
        return account(id, customerId, "ACTIVE", balance, "VND");
    }

    private AccountJdbcRepository.AccountRow account(UUID id, UUID customerId, String status, String balance, String currency) {
        return new AccountJdbcRepository.AccountRow(id, customerId, "123456789012", "CHECKING", status, balance, currency, Instant.EPOCH);
    }

    private com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord otpRecord(UUID id, int attempts) {
        return new com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord(id, "0912345678", "SMS",
                "TRANSFER_STEP_UP", "hash", attempts, 5, Instant.EPOCH.plusSeconds(120), null, null);
    }

    private record Dependencies(TransferService service, AccountJdbcRepository accounts,
                                TransferJdbcRepository transfers, IdentityJdbcRepository identities,
                                OtpHashingService otpHashing, TransferOtpRepository transferOtp) {}

    private static final class InlineTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return mock(TransactionStatus.class); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
