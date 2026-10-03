package com.bank.simulator.identity.application;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingServiceTest {

    @Test
    void recoveryConfirmWithUnknownUserFailsAsGenericInvalidOtp() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        when(repository.findByIdentifier("missing@example.test", "EMAIL")).thenReturn(null);
        OnboardingService service = service(repository);

        assertThatThrownBy(() -> service.confirmRecovery("missing@example.test", "EMAIL", "123456", "new-password-123"))
                .isInstanceOfSatisfying(com.bank.simulator.shared.error.ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("OTP_INVALID");
                });
        verify(repository, never()).updatePassword(any(UUID.class), anyString());
    }

    @Test
    void operatorMustHaveAllowedRole() {
        OnboardingService service = service(mock(IdentityJdbcRepository.class));

        assertThatThrownBy(() -> service.sendOperatorOtp(new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(), java.util.Set.of("AUDITOR")), "0912345678"))
                .isInstanceOfSatisfying(com.bank.simulator.shared.error.ApiException.class, exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private OnboardingService service(IdentityJdbcRepository repository) {
        return new OnboardingService(repository, mock(OtpChallengeService.class), mock(PasswordHashingService.class),
                mock(PinHashingService.class), mock(RefreshSessionService.class), mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
                mock(AccountNumberGenerator.class), mock(PinCredentialService.class));
    }
}
