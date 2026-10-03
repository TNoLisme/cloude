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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {
    @Test
    void customerCannotCreateTransferWithNonCanonicalAmount() {
        var d = dependencies();
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "02000", "VND", "001234", null);
        assertThatThrownBy(() -> d.service.create(actor, command, "abcdefghijklmnop")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency, d.pinCredentials, d.audit);
    }

    @Test
    void invalidPinStopsTransferBeforeAccountMutation() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.pinCredentials.verify(customerId, "001234")).thenReturn(PinCredentialService.VerifyResult.INVALID);
        assertThatThrownBy(() -> d.service.create(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null),
                "abcdefghijklmnop")).isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency, d.audit);
    }

    @Test
    void missingDestinationStopsTransferAfterOrderedLockAttempt() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.pinCredentials.verify(customerId, "001234")).thenReturn(PinCredentialService.VerifyResult.VALID);
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(null);
        assertThatThrownBy(() -> d.service.create(new AuthenticatedActor(userId, Set.of("CUSTOMER")),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop")).isInstanceOf(ApiException.class);
        verify(d.accounts).lockPair(sourceId, destinationId);
        verify(d.accounts, never()).debit(any(), anyString());
    }

    @Test
    void invalidOtpIncrementsAttemptBeforeReturningBadRequest() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(120)));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "10000000"), account(destinationId, UUID.randomUUID(), "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("000000", "hash")).thenReturn(false);
        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(d.transferOtp).updateAttempts(challengeId, 1, null);
        verify(d.accounts, never()).debit(any(), anyString());
    }

    @Test
    void fifthInvalidOtpFailsTransferAndInvalidatesChallenge() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(120)));
        when(d.accounts.lockPair(sourceId, destinationId)).thenReturn(new AccountJdbcRepository.AccountPair(
                account(sourceId, customerId, "10000000"), account(destinationId, UUID.randomUUID(), "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 4));
        when(d.otpHashing.matches("000000", "hash")).thenReturn(false);
        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        verify(d.transferOtp).updateAttempts(challengeId, 5, Instant.EPOCH);
        verify(d.transfers).fail(transferId, "OTP_INVALID", Instant.EPOCH);
    }

    @Test
    void expiredOtpCommitsExpiredTransferBeforeErrorResponse() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), transferId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.EPOCH.minusSeconds(120), Instant.EPOCH));
        assertThatThrownBy(() -> d.service.confirm(new AuthenticatedActor(userId, Set.of("CUSTOMER")), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        verify(d.transfers).expire(transferId, Instant.EPOCH);
        verifyNoInteractions(d.accounts, d.transferOtp);
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
        var txManager = new InlineTransactionManager();
        var service = new TransferService(accounts, transfers, idempotency, transferOtp, identities,
                otpHashing, otpRepository, sender, pinCredentials, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), txManager);
        return new Dependencies(service, accounts, transfers, idempotency, identities, otpHashing, transferOtp, pinCredentials, audit);
    }

    private TransferJdbcRepository.TransferRow transfer(UUID id, UUID sourceId, UUID destinationId, UUID challengeId,
                                                         Instant createdAt, Instant expiresAt) {
        return new TransferJdbcRepository.TransferRow(id, sourceId, destinationId, "6000000", "VND", "AWAITING_OTP",
                null, null, challengeId, createdAt, expiresAt, null);
    }

    private IdentityJdbcRepository.CustomerRecord customer(UUID customerId, UUID userId) {
        return new IdentityJdbcRepository.CustomerRecord(customerId, userId, "Customer", null, Instant.EPOCH,
                "0912345678", "c@example.test", true);
    }

    private AccountJdbcRepository.AccountRow account(UUID id, UUID customerId, String balance) {
        return new AccountJdbcRepository.AccountRow(id, customerId, "123456789012", "CHECKING", "ACTIVE", balance,
                "VND", Instant.EPOCH);
    }

    private com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord otpRecord(UUID id, int attempts) {
        return new com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord(id, "0912345678", "SMS",
                "TRANSFER_STEP_UP", "hash", attempts, 5, Instant.EPOCH.plusSeconds(120), null, null);
    }

    private record Dependencies(TransferService service, AccountJdbcRepository accounts,
                                TransferJdbcRepository transfers, IdempotencyJdbcRepository idempotency,
                                IdentityJdbcRepository identities, OtpHashingService otpHashing,
                                TransferOtpRepository transferOtp, PinCredentialService pinCredentials, AuditWriter audit) {}

    private static final class InlineTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return mock(TransactionStatus.class); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
