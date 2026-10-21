package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferOtpRepository;
import org.junit.jupiter.api.Test;
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

class TransferServiceTest {
    private static final Instant NOW = Instant.EPOCH;

    @Test
    void nonCanonicalAmountRejectedWithAmountInvalidBeforePinOrMoneyState() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        var command = new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "02000", "VND", "001234", null);
        assertThatThrownBy(() -> d.service.create(actor(userId), command, "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("AMOUNT_INVALID");
                });
        verify(d.identities, never()).verifyPin(any(), anyString());
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency, d.audit);
    }

    @Test
    void invalidPinReturns400PinInvalidBeforeMoneyState() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.INVALID);
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("PIN_INVALID");
                });
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency, d.audit);
    }

    @Test
    void lockedPinReturns403PinLocked() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.LOCKED);
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.code()).isEqualTo("PIN_LOCKED");
                });
    }

    @Test
    void ut04SelfTransferRejectedAsValidationError() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), accountId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(accountId, accountId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("VALIDATION_ERROR");
                });
        verify(d.identities, never()).verifyPin(any(), anyString());
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency);
    }

    @Test
    void shortOrNonAsciiIdempotencyKeyRejectedAsValidationError() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        var command = new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null);
        assertThatThrownBy(() -> d.service.create(actor(userId), command, "short-key"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> d.service.create(actor(userId), command, "abcdefghijklmno p"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void missingAccountReturns404AccountNotFoundAfterLockAttempt() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(claim());
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(null);
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("ACCOUNT_NOT_FOUND");
                });
        verify(d.accounts).lockAccountsAscending(sourceId, destinationId);
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void sourceAccountNotOwnedReturns404AccountNotFound() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(claim());
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, UUID.randomUUID(), "ACTIVE", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("ACCOUNT_NOT_FOUND");
                });
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void blockedAccountReturns409AccountNotEligible() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(claim());
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "BLOCKED", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void insufficientBalanceReturns409InsufficientFunds() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(claim());
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "1000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("INSUFFICIENT_FUNDS");
                });
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void smallTransferCompletesNineStepFlowWith201Completion() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq("abcdefghijklmnop"), anyString(), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(
                        new IdempotencyJdbcRepository.IdempotencyRecord(claimId, "request-hash", null, null, null, Instant.MAX), true));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transfers.create(eq(sourceId), eq(destinationId), eq("50000"), eq("VND"), eq("COMPLETED"),
                isNull(), isNull(), eq(claimId), eq(NOW), isNull())).thenReturn(transferId);
        var row = new TransferJdbcRepository.TransferRow(transferId, sourceId, destinationId, "50000", "VND",
                "COMPLETED", null, null, null, NOW, null, NOW);
        when(d.transfers.find(transferId)).thenReturn(row);

        var result = d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "50000", "VND", "001234", null),
                "abcdefghijklmnop");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.replayed()).isFalse();
        assertThat(result.transferId()).isEqualTo(transferId);
        verify(d.accounts).debit(sourceId, new BigDecimal("50000"));
        verify(d.accounts).credit(destinationId, new BigDecimal("50000"));
        verify(d.transfers).complete(transferId, NOW);
        verify(d.audit).record(eq(userId), eq("CUSTOMER"), eq("TRANSFER_COMPLETED"), eq("TRANSFER"), eq(transferId),
                eq("SUCCESS"), isNull(), anyString());
        verify(d.idempotency).complete(eq(claimId), eq(201), anyString(), eq(transferId));
        verify(d.events).publishEvent(any(TransferCommittedEvent.class));
    }

    @Test
    void sameKeyInProgressClaimReturnsConflictWithoutCreatingAnotherTransfer() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        String requestHash = TransferPolicy.payloadHash(sourceId, destinationId, new BigDecimal("2000"), "VND", null);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop"))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), requestHash, null, null, null, Instant.MAX));

        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                });
        verify(d.idempotency, never()).createOrFind(any(), anyString(), anyString(), anyString(), any(), any());
        verify(d.transfers, never()).create(any(), any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void sameKeyDifferentPayloadReturns409IdempotencyKeyReused() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", "abcdefghijklmnop"))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), "different-hash",
                        null, null, null, Instant.MAX));

        assertThatThrownBy(() -> d.service.create(actor(userId),
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        verifyNoInteractions(d.accounts);
    }

    @Test
    void invalidOtpIncrementsAttemptBeforeReturningBadRequest() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId, Instant.EPOCH, Instant.EPOCH.plusSeconds(120)));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 0));
        when(d.otpHashing.matches("000000", "hash")).thenReturn(false);
        assertThatThrownBy(() -> d.service.confirm(actor(userId), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(d.transferOtp).updateAttempts(challengeId, 1, null);
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void fifthInvalidOtpFailsTransferWithAttemptsExceededCode() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID(), transferId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, challengeId, Instant.EPOCH, Instant.EPOCH.plusSeconds(120)));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transferOtp.lock(challengeId, Instant.EPOCH)).thenReturn(otpRecord(challengeId, 4));
        when(d.otpHashing.matches("000000", "hash")).thenReturn(false);
        assertThatThrownBy(() -> d.service.confirm(actor(userId), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) e).code()).isEqualTo("STATE_CONFLICT");
                });
        verify(d.transferOtp).updateAttempts(challengeId, 5, Instant.EPOCH);
        verify(d.transfers).fail(transferId, "OTP_ATTEMPTS_EXCEEDED", Instant.EPOCH);
    }

    @Test
    void expiredOtpCommitsExpiredTransferBeforeErrorResponse() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), transferId = UUID.randomUUID();
        var d = dependencies();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.transfers.lock(transferId)).thenReturn(transfer(transferId, sourceId, destinationId, UUID.randomUUID(), Instant.EPOCH.minusSeconds(120), Instant.EPOCH));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.confirm(actor(userId), transferId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) e).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        verify(d.accounts).lockAccountsAscending(sourceId, destinationId);
        verify(d.transfers).expire(transferId, Instant.EPOCH);
        verifyNoInteractions(d.transferOtp);
    }

    private IdempotencyJdbcRepository.IdempotencyClaim claim() {
        return new IdempotencyJdbcRepository.IdempotencyClaim(
                new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), "request-hash",
                        null, null, null, Instant.MAX), true);
    }

    private AuthenticatedActor actor(UUID userId) {
        return new AuthenticatedActor(userId, Set.of("CUSTOMER"));
    }

    private IdentityModuleApi.CustomerView customer(UUID customerId, UUID userId) {
        return new IdentityModuleApi.CustomerView(customerId, userId, "Customer", "0912345678", true);
    }

    private AccountModuleApi.LockedAccount locked(UUID id, UUID customerId, String status, String balance) {
        return new AccountModuleApi.LockedAccount(id, customerId, status, balance, "VND");
    }

    private TransferJdbcRepository.TransferRow transfer(UUID id, UUID sourceId, UUID destinationId, UUID challengeId, Instant createdAt, Instant expiresAt) {
        return new TransferJdbcRepository.TransferRow(id, sourceId, destinationId, "6000000", "VND", "AWAITING_OTP", null, null, challengeId, createdAt, expiresAt, null);
    }

    private com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord otpRecord(UUID id, int attempts) {
        return new com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord(id, "0912345678", "SMS", "TRANSFER_STEP_UP", "hash", attempts, 5, Instant.EPOCH.plusSeconds(120), null, null);
    }

    private Dependencies dependencies() {
        var accounts = mock(AccountModuleApi.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var transferOtp = mock(TransferOtpRepository.class);
        var otpHashing = mock(OtpHashingService.class);
        var otpRepository = mock(OtpChallengeRepository.class);
        var sender = mock(OtpSender.class);
        var identities = mock(IdentityModuleApi.class);
        var audit = mock(AuditWriter.class);
        var events = mock(ApplicationEventPublisher.class);
        var service = new TransferService(accounts, transfers, idempotency, transferOtp, otpHashing, otpRepository,
                sender, identities, audit, Clock.fixed(NOW, ZoneOffset.UTC), events, new InlineTransactionManager());
        return new Dependencies(service, accounts, transfers, idempotency, transferOtp, otpHashing, identities, audit, events);
    }

    private record Dependencies(TransferService service, AccountModuleApi accounts, TransferJdbcRepository transfers,
                                IdempotencyJdbcRepository idempotency, TransferOtpRepository transferOtp,
                                OtpHashingService otpHashing, IdentityModuleApi identities, AuditWriter audit,
                                ApplicationEventPublisher events) {}

    private static final class InlineTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return mock(TransactionStatus.class); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
