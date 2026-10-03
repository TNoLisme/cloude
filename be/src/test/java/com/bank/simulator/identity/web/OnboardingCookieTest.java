package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnboardingCookieTest {

    @Test
    void loginCookieUsesRemainingTtlAndSecureOnlyForHttps() {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        Instant expires = now.plusSeconds(600);
        var session = new RefreshSessionService.IssuedRefreshSession(UUID.randomUUID(), UUID.randomUUID(),
                List.of("CUSTOMER"), "opaque-token", expires);
        var login = new OnboardingService.LoginResult("access", session,
                new OnboardingService.UserSummary(UUID.randomUUID(), UUID.randomUUID(), "Customer",
                        "0912345678", "customer@example.test", List.of("CUSTOMER"), false));
        OnboardingService service = mock(OnboardingService.class);
        when(service.login("0912345678", "password")).thenReturn(login);
        OnboardingController controller = new OnboardingController(service,
                Clock.fixed(now, ZoneOffset.UTC));

        MockHttpServletRequest secureRequest = new MockHttpServletRequest();
        secureRequest.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        controller.login(new OnboardingController.LoginRequest("0912345678", "password"), secureRequest, secureResponse);
        assertThat(secureResponse.getHeader("Set-Cookie"))
                .contains("Max-Age=600", "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");

        MockHttpServletRequest plainRequest = new MockHttpServletRequest();
        MockHttpServletResponse plainResponse = new MockHttpServletResponse();
        controller.login(new OnboardingController.LoginRequest("0912345678", "password"), plainRequest, plainResponse);
        assertThat(plainResponse.getHeader("Set-Cookie"))
                .contains("Max-Age=600", "Path=/api/v1/auth", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
    }
}
