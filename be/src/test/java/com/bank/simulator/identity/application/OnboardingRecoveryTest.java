package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
        OnboardingService service = service(repository, otp, provider);

        var response = service.initiateRecovery("missing@example.test", "EMAIL");

        assertThat(response.message()).isEqualTo("If the information is registered, an OTP has been sent to the selected channel.");
        verify(otp, never()).issue(anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void recoveryConfirmationForUnknownIdentifierAuditsWithoutLeakingIdentifier() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        AuditWriter audit = mock(AuditWriter.class);
        ObjectProvider<AuditWriter> provider = provider(audit);
        when(repository.findByIdentifier("missing@example.test", "EMAIL")).thenReturn(null);
        OnboardingService service = service(repository, otp, provider);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirmRecovery(
                        "missing@example.test", "EMAIL", "012345", "password-123456789"))
                .isInstanceOfSatisfying(com.bank.simulator.shared.error.ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OTP_INVALID"));
        verifyNoInteractions(audit);
    }

    private ObjectProvider<AuditWriter> provider(AuditWriter audit) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        return provider;
    }

    private OnboardingService service(IdentityJdbcRepository repository, OtpChallengeService otp,
                                     ObjectProvider<AuditWriter> audit) {
        return new OnboardingService(repository, otp, mock(PasswordHashingService.class),
                mock(PinHashingService.class), mock(RefreshSessionService.class), mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(AccountNumberGenerator.class),
                mock(PinCredentialService.class), audit);
    }
}
