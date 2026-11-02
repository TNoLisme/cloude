package com.bank.simulator.identity.application;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingRegistrationTest {

    @Test
    void duplicatePhoneRaceMapsUniqueViolationToStableConflict() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        when(otp.consumeLatest("0912345678", "SMS", "REGISTRATION", "123456"))
                .thenReturn(OtpChallengeService.ConsumeResult.VALID);
        when(repository.findByPhone("0912345678")).thenReturn(null, new IdentityJdbcRepository.UserRecord(
                UUID.randomUUID(), "0912345678", "user@example.test", "hash", true, UUID.randomUUID(),
                "User Name", Instant.parse("2026-10-02T00:00:00Z"), false));
        when(repository.findByIdentifier("user@example.test", "EMAIL")).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uq_users_phone"))
                .when(repository).createUser(any(UUID.class), anyString(), anyString(), nullable(String.class), anyString(), any(Instant.class));
        OnboardingService service = service(repository, otp);

        assertThatThrownBy(() -> service.register("0912345678", "user@example.test", "very-long-password",
                "User Name", null, "123456"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("PHONE_ALREADY_REGISTERED");
                });
        verify(repository, never()).createCustomer(any(UUID.class), any(UUID.class), anyString(), any(), any(Instant.class));
    }

    private OnboardingService service(IdentityJdbcRepository repository, OtpChallengeService otp) {
        return new OnboardingService(repository, otp, mock(PasswordHashingService.class), mock(PinHashingService.class),
                mock(RefreshSessionService.class), mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC), mock(AccountNumberGenerator.class),
                mock(PinCredentialService.class));
    }
}
