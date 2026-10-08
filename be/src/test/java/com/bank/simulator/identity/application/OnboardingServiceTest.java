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
import java.util.List;
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
    void operatorMustHaveAllowedRole() {
        OnboardingService service = service(mock(IdentityJdbcRepository.class));

        assertThatThrownBy(() -> service.sendOperatorOtp(new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(), java.util.Set.of("AUDITOR")), "0912345678"))
                .isInstanceOfSatisfying(com.bank.simulator.shared.error.ApiException.class, exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void refreshRebuildsCustomerSummaryFromCurrentIdentity() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        RefreshSessionService sessions = mock(RefreshSessionService.class);
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var session = new RefreshSessionService.IssuedRefreshSession(UUID.randomUUID(), userId,
                List.of("CUSTOMER"), "new-token", Instant.parse("2026-10-09T00:00:00Z"));
        when(sessions.rotate("old-token")).thenReturn(session);
        when(repository.findByUserId(userId)).thenReturn(new IdentityJdbcRepository.UserRecord(userId,
                "0912345678", "customer@example.test", "unused-hash", true, customerId, "Customer Name",
                Instant.parse("2026-10-01T00:00:00Z"), true));

        var result = service(repository, sessions).refreshSession("old-token");

        assertThat(result.session()).isSameAs(session);
        assertThat(result.user()).isEqualTo(new OnboardingService.UserSummary(userId, customerId,
                "Customer Name", "0912345678", "customer@example.test", List.of("CUSTOMER"), true));
    }

    @Test
    void refreshRejectsInactiveUser() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        RefreshSessionService sessions = mock(RefreshSessionService.class);
        UUID userId = UUID.randomUUID();
        when(sessions.rotate("old-token")).thenReturn(new RefreshSessionService.IssuedRefreshSession(
                UUID.randomUUID(), userId, List.of("CUSTOMER"), "new-token", Instant.parse("2026-10-09T00:00:00Z")));
        when(repository.findByUserId(userId)).thenReturn(new IdentityJdbcRepository.UserRecord(userId,
                "0912345678", "customer@example.test", "unused-hash", false, UUID.randomUUID(), "Customer",
                Instant.parse("2026-10-01T00:00:00Z"), false));

        assertThatThrownBy(() -> service(repository, sessions).refreshSession("old-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OnboardingService service(IdentityJdbcRepository repository) {
        return service(repository, mock(RefreshSessionService.class));
    }

    private OnboardingService service(IdentityJdbcRepository repository, RefreshSessionService sessions) {
        return new OnboardingService(repository, mock(OtpChallengeService.class), mock(PasswordHashingService.class),
                mock(PinHashingService.class), sessions, mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
                mock(AccountNumberGenerator.class), mock(PinCredentialService.class));
    }
}
