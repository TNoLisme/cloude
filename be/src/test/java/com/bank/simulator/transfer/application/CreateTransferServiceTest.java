package com.bank.simulator.transfer.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import com.bank.simulator.transfer.domain.TransferPolicy;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
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

class CreateTransferServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String KEY = "abcdefghijklmnop";

    @Test
    void nonCanonicalAmountRejectedWithAmountInvalidBeforePinOrMoneyState() {
        var d = dependencies();
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        var command = new CreateTransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "02000", "VND", "001234", null);
        assertThatThrownBy(() -> d.service.create(actor(userId), command, KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("AMOUNT_INVALID");
                });
        verify(d.identities, never()).verifyPin(any(), anyString());
        verifyNoInteractions(d.accounts, d.transfers, d.idempotency, d.audit, d.failureInjector);
    }

    @Test
    void invalidPinReturns400PinInvalidBeforeMoneyState() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.INVALID);
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null),
                KEY))
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
                new CreateTransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null),
                KEY))
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
                new CreateTransferService.CreateCommand(accountId, accountId, "2000", "VND", "001234", null), KEY))
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
        var command = new CreateTransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "2000", "VND", "001234", null);
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
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(null);
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
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
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, UUID.randomUUID(), "ACTIVE", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
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
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "BLOCKED", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
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
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "1000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("INSUFFICIENT_FUNDS");
                });
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void smallTransferCompletesWith201IdempotencyAndRiskEvent() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq(KEY), anyString(), any(), any()))
                .thenReturn(createdClaim(claimId));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "1000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.transfers.create(eq(sourceId), eq(destinationId), eq("50000"), eq("VND"), eq("COMPLETED"),
                any(), isNull(), eq(claimId), eq(NOW), isNull())).thenReturn(transferId);
        var row = new TransferJdbcRepository.TransferRow(transferId, sourceId, destinationId, "50000", "VND",
                "COMPLETED", null, null, null, NOW, null, NOW);
        when(d.transfers.find(transferId)).thenReturn(row);

        var result = d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "50000", "VND", "001234", null), KEY);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.challengeRequired()).isFalse();
        assertThat(result.replayed()).isFalse();
        assertThat(result.transferId()).isEqualTo(transferId);
        verify(d.accounts).debit(sourceId, new BigDecimal("50000"));
        verify(d.failureInjector).afterDebitBeforeCredit();
        verify(d.accounts).credit(destinationId, new BigDecimal("50000"));
        verify(d.identities, never()).issueOtp(any(), anyString());
        verify(d.audit).record(eq(userId), eq("CUSTOMER"), eq("TRANSFER_COMPLETED"), eq("TRANSFER"), eq(transferId),
                eq("SUCCESS"), isNull(), anyString());
        verify(d.idempotency).complete(eq(claimId), eq(201), anyString(), eq(transferId));
        verify(d.events).publishEvent(any(TransferCommittedEvent.class));
    }

    @Test
    void largeTransferIssuesOtpInsideMoneyTransactionAndDispatchesAfterCommit() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(120);
        var issue = new IdentityModuleApi.OtpIssue(challengeId, customerId, "0912345678", "123456", expiresAt);
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq(KEY), anyString(), any(), any()))
                .thenReturn(createdClaim(claimId));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.identities.issueOtp(customerId, "TRANSFER_STEP_UP")).thenReturn(issue);
        when(d.transfers.create(eq(sourceId), eq(destinationId), eq("5000001"), eq("VND"), eq("AWAITING_OTP"),
                any(), eq(challengeId), eq(claimId), eq(NOW), eq(expiresAt))).thenReturn(transferId);

        var result = d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "5000001", "VND", "001234", null), KEY);

        assertThat(result.challengeRequired()).isTrue();
        assertThat(result.status()).isEqualTo("AWAITING_OTP");
        assertThat(result.expiresInSeconds()).isEqualTo(120);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        verify(d.accounts, never()).debit(any(), any());
        verify(d.accounts, never()).credit(any(), any());
        verify(d.idempotency).complete(eq(claimId), eq(200), anyString(), eq(transferId));
        verify(d.identities).dispatchOtp(issue);
        verify(d.audit).record(eq(userId), eq("CUSTOMER"), eq("TRANSFER_OTP_REQUESTED"), eq("TRANSFER"), eq(transferId),
                eq("SUCCESS"), isNull(), anyString());
    }

    @Test
    void dispatchFailureCompensatesTransferAndChallengeThenReturns503() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(120);
        var issue = new IdentityModuleApi.OtpIssue(challengeId, customerId, "0912345678", "123456", expiresAt);
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq(KEY), anyString(), any(), any()))
                .thenReturn(createdClaim(claimId));
        when(d.accounts.lockAccountsAscending(sourceId, destinationId)).thenReturn(
                new AccountModuleApi.LockedAccountPair(
                        locked(sourceId, customerId, "ACTIVE", "10000000"),
                        locked(destinationId, UUID.randomUUID(), "ACTIVE", "0")));
        when(d.identities.issueOtp(customerId, "TRANSFER_STEP_UP")).thenReturn(issue);
        when(d.transfers.create(eq(sourceId), eq(destinationId), eq("5000001"), eq("VND"), eq("AWAITING_OTP"),
                any(), eq(challengeId), eq(claimId), eq(NOW), eq(expiresAt))).thenReturn(transferId);
        doThrow(new IllegalStateException("sender down")).when(d.identities).dispatchOtp(issue);
        when(d.transfers.lock(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId, expiresAt));

        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "5000001", "VND", "001234", null), KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) error).code()).isEqualTo("SERVICE_UNAVAILABLE");
                });
        verify(d.transfers).fail(transferId, "OTP_DISPATCH_FAILED", NOW);
        verify(d.identities).invalidateOtp(challengeId);
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void replayWhileAwaitingOtpReturnsCurrentStateAndRemainingTtlWithoutNewDispatch() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(90);
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        String hash = TransferPolicy.payloadHash(sourceId, destinationId, new BigDecimal("5000001"), "VND", null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq(KEY), eq(hash), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(new IdempotencyJdbcRepository.IdempotencyRecord(
                        claimId, hash, 200, "{}", transferId, NOW.plusSeconds(86400)), false));
        when(d.transfers.find(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId, expiresAt));

        var result = d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "5000001", "VND", "001234", null), KEY);

        assertThat(result.challengeRequired()).isTrue();
        assertThat(result.replayed()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(90);
        assertThat(result.status()).isEqualTo("AWAITING_OTP");
        verify(d.identities, never()).issueOtp(any(), anyString());
        verify(d.identities, never()).dispatchOtp(any());
        verify(d.accounts, never()).debit(any(), any());
        verify(d.transfers, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayAfterExpiryReturnsTerminalExpiredWithoutDispatch() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID(), transferId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID(), challengeId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        String hash = TransferPolicy.payloadHash(sourceId, destinationId, new BigDecimal("5000001"), "VND", null);
        when(d.idempotency.createOrFind(eq(userId), eq("createTransfer"), eq(KEY), eq(hash), any(), any()))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyClaim(new IdempotencyJdbcRepository.IdempotencyRecord(
                        claimId, hash, 200, "{}", transferId, NOW.plusSeconds(86400)), false));
        when(d.transfers.find(transferId)).thenReturn(awaiting(transferId, sourceId, destinationId, challengeId,
                NOW.minusSeconds(1)));

        var result = d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "5000001", "VND", "001234", null), KEY);

        assertThat(result.status()).isEqualTo("EXPIRED");
        assertThat(result.challengeRequired()).isFalse();
        assertThat(result.replayed()).isTrue();
        verify(d.identities, never()).dispatchOtp(any());
        verify(d.identities, never()).issueOtp(any(), anyString());
    }

    @Test
    void sameKeyInProgressClaimReturnsConflictWithoutCreatingAnotherTransfer() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID(), sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        String requestHash = TransferPolicy.payloadHash(sourceId, destinationId, new BigDecimal("2000"), "VND", null);
        when(d.idempotency.find(userId, "createTransfer", KEY))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), requestHash, null, null, null, Instant.MAX));

        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                });
        verify(d.idempotency, never()).createOrFind(any(), anyString(), anyString(), anyString(), any(), any());
        verify(d.transfers, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(d.accounts, never()).debit(any(), any());
    }

    @Test
    void sameKeyDifferentPayloadReturns409IdempotencyKeyReused() {
        UUID userId = UUID.randomUUID(), customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID(), destinationId = UUID.randomUUID();
        var d = dependencies();
        when(d.identities.findCustomerByUserId(userId)).thenReturn(customer(customerId, userId));
        when(d.identities.verifyPin(customerId, "001234")).thenReturn(IdentityModuleApi.PinVerificationResult.VALID);
        when(d.idempotency.find(userId, "createTransfer", KEY))
                .thenReturn(new IdempotencyJdbcRepository.IdempotencyRecord(UUID.randomUUID(), "different-hash",
                        null, null, null, Instant.MAX));

        assertThatThrownBy(() -> d.service.create(actor(userId),
                new CreateTransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null), KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        verifyNoInteractions(d.accounts);
    }

    private IdempotencyJdbcRepository.IdempotencyClaim createdClaim(UUID claimId) {
        return new IdempotencyJdbcRepository.IdempotencyClaim(
                new IdempotencyJdbcRepository.IdempotencyRecord(claimId, "request-hash", null, null, null, Instant.MAX), true);
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

    private TransferJdbcRepository.TransferRow awaiting(UUID id, UUID sourceId, UUID destinationId, UUID challengeId,
                                                      Instant expiresAt) {
        return new TransferJdbcRepository.TransferRow(id, sourceId, destinationId, "5000001", "VND", "AWAITING_OTP",
                null, null, challengeId, NOW, expiresAt, null);
    }

    private Dependencies dependencies() {
        var accounts = mock(AccountModuleApi.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var identities = mock(IdentityModuleApi.class);
        var audit = mock(AuditWriter.class);
        var events = mock(ApplicationEventPublisher.class);
        var failureInjector = mock(TransferFailureInjector.class);
        when(idempotency.createOrFind(any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(createdClaim(UUID.randomUUID()));
        var service = new CreateTransferService(accounts, transfers, idempotency, identities, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), events, new InlineTransactionManager(), failureInjector);
        return new Dependencies(service, accounts, transfers, idempotency, identities, audit, events, failureInjector);
    }

    private record Dependencies(CreateTransferService service, AccountModuleApi accounts,
                                TransferJdbcRepository transfers, IdempotencyJdbcRepository idempotency,
                                IdentityModuleApi identities, AuditWriter audit, ApplicationEventPublisher events,
                                TransferFailureInjector failureInjector) {}

    private static final class InlineTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return mock(TransactionStatus.class); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
