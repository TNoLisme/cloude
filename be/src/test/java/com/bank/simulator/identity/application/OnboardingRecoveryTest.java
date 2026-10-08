package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenRepository;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnboardingRecoveryTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recoveryInitiateAuditsNoTargetForUnknownIdentifierAndReturnsGenericBody() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        AuditWriter audit = mock(AuditWriter.class);
        ObjectProvider<AuditWriter> provider = provider(audit);
        when(repository.findByIdentifier("missing@example.test", "EMAIL")).thenReturn(null);
        RecoveryService service = service(repository, otp, provider);

        var response = service.initiate("missing@example.test", "EMAIL");

        assertThat(response.message()).isEqualTo("If the information is registered, an OTP has been sent to the selected channel.");
        verify(otp, never()).issue(anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void recoveryVerificationForUnknownIdentifierAuditsWithoutLeakingIdentifier() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        AuditWriter audit = mock(AuditWriter.class);
        ObjectProvider<AuditWriter> provider = provider(audit);
        when(repository.findByIdentifier("missing@example.test", "EMAIL")).thenReturn(null);
        RecoveryService service = service(repository, otp, provider);

        assertThat(service.verify("missing@example.test", "EMAIL", "012345").valid()).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void newInitiateInvalidatesOlderResetTokenBeforeIssuingOtp() {
        IdentityJdbcRepository identities = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        RecoveryTokenRepository tokens = mock(RecoveryTokenRepository.class);
        UUID userId = UUID.randomUUID();
        when(identities.findByIdentifier("0912345678", "SMS")).thenReturn(user(userId));
        RecoveryService service = service(identities, otp, tokens, mock(RecoveryTokenCodec.class));

        service.initiate("0912345678", "SMS");

        var order = inOrder(tokens, otp);
        order.verify(tokens).invalidateActiveForUser(userId, Instant.EPOCH);
        order.verify(otp).issue("0912345678", "SMS", "RECOVERY");
    }

    @Test
    void validOtpIssuesFiveMinuteTokenAndWrongOtpDoesNot() {
        IdentityJdbcRepository identities = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        RecoveryTokenRepository tokens = mock(RecoveryTokenRepository.class);
        RecoveryTokenCodec codec = mock(RecoveryTokenCodec.class);
        UUID userId = UUID.randomUUID();
        when(identities.findByIdentifier("0912345678", "SMS")).thenReturn(user(userId));
        when(identities.findByUserId(userId)).thenReturn(user(userId));
        when(otp.consumeLatest("0912345678", "SMS", "RECOVERY", "123456"))
                .thenReturn(OtpChallengeService.ConsumeResult.INVALID);
        when(otp.consumeLatest("0912345678", "SMS", "RECOVERY", "654321"))
                .thenReturn(OtpChallengeService.ConsumeResult.VALID);
        when(codec.generate()).thenReturn("new-opaque-token");
        when(codec.hash("new-opaque-token")).thenReturn("hashed-token");
        RecoveryService service = service(identities, otp, tokens, codec);

        assertThat(service.verify("0912345678", "SMS", "123456").valid()).isFalse();
        verify(tokens, never()).create(any(), any(), anyString(), any(), any());
        assertThat(service.verify("0912345678", "SMS", "654321"))
                .isEqualTo(new RecoveryService.VerifyResult("new-opaque-token", 300));
        verify(tokens).create(any(UUID.class), eq(userId), eq("hashed-token"), eq(Instant.EPOCH),
                eq(Instant.EPOCH.plusSeconds(300)));
    }

    @Test
    void confirmConsumesTokenChangesPasswordAndRevokesSessions() {
        IdentityJdbcRepository identities = mock(IdentityJdbcRepository.class);
        RecoveryTokenRepository tokens = mock(RecoveryTokenRepository.class);
        RecoveryTokenCodec codec = mock(RecoveryTokenCodec.class);
        PasswordHashingService passwords = mock(PasswordHashingService.class);
        RefreshSessionService sessions = mock(RefreshSessionService.class);
        UUID userId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        when(codec.hash("opaque-token")).thenReturn("hashed-token");
        when(tokens.findUserId("hashed-token")).thenReturn(userId);
        when(tokens.lock("hashed-token")).thenReturn(new RecoveryTokenRepository.RecoveryTokenRecord(
                proofId, userId, Instant.EPOCH.plusSeconds(300), null, null));
        when(identities.findByUserId(userId)).thenReturn(user(userId));
        when(passwords.encode("new-password-1234")).thenReturn("encoded");
        RecoveryService service = new RecoveryService(identities, mock(OtpChallengeService.class), tokens,
                codec, passwords, sessions, provider(mock(AuditWriter.class)), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        service.confirm("opaque-token", "new-password-1234");

        verify(identities).updatePassword(userId, "encoded");
        verify(tokens).consume(proofId, Instant.EPOCH);
        verify(sessions).revokeAll(userId);
    }

    @Test
    void expiredOrUsedResetTokenCannotChangePassword() {
        IdentityJdbcRepository identities = mock(IdentityJdbcRepository.class);
        RecoveryTokenRepository tokens = mock(RecoveryTokenRepository.class);
        RecoveryTokenCodec codec = mock(RecoveryTokenCodec.class);
        UUID userId = UUID.randomUUID();
        when(codec.hash("opaque-token")).thenReturn("hashed-token");
        when(tokens.findUserId("hashed-token")).thenReturn(userId);
        when(tokens.lock("hashed-token")).thenReturn(
                new RecoveryTokenRepository.RecoveryTokenRecord(UUID.randomUUID(), userId, Instant.EPOCH, null, null),
                new RecoveryTokenRepository.RecoveryTokenRecord(UUID.randomUUID(), userId, Instant.EPOCH.plusSeconds(300), Instant.EPOCH, null));
        RecoveryService service = service(identities, mock(OtpChallengeService.class), tokens, codec);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.confirm("opaque-token", "new-password-1234")))
                .isInstanceOf(com.bank.simulator.shared.error.ApiException.class)
                .hasMessageContaining("Recovery token is invalid");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.confirm("opaque-token", "new-password-1234")))
                .isInstanceOf(com.bank.simulator.shared.error.ApiException.class);
        verify(identities, never()).updatePassword(any(), anyString());
    }

    private IdentityJdbcRepository.UserRecord user(UUID userId) {
        return new IdentityJdbcRepository.UserRecord(userId, "0912345678", "test@example.test", "hash",
                true, UUID.randomUUID(), "Test", Instant.EPOCH, false);
    }

    private ObjectProvider<AuditWriter> provider(AuditWriter audit) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        return provider;
    }

    private RecoveryService service(IdentityJdbcRepository repository, OtpChallengeService otp,
                                     ObjectProvider<AuditWriter> audit) {
        return new RecoveryService(repository, otp, mock(RecoveryTokenRepository.class),
                mock(RecoveryTokenCodec.class), mock(PasswordHashingService.class),
                mock(RefreshSessionService.class), audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private RecoveryService service(IdentityJdbcRepository repository, OtpChallengeService otp,
                                    RecoveryTokenRepository tokens, RecoveryTokenCodec codec) {
        return new RecoveryService(repository, otp, tokens, codec, mock(PasswordHashingService.class),
                mock(RefreshSessionService.class), provider(mock(AuditWriter.class)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }
}
