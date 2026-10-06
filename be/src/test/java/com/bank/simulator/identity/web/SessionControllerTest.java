package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.infrastructure.security.CsrfTokenService;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.api.Problem;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {

    private final RefreshSessionService sessions = mock(RefreshSessionService.class);
    private final OnboardingService onboarding = mock(OnboardingService.class);
    private final CsrfTokenService csrf = mock(CsrfTokenService.class);
    private final JwtAccessTokenCodec accessTokens = mock(JwtAccessTokenCodec.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
    private final SessionController controller = new SessionController(sessions, onboarding, csrf, accessTokens, "refresh_token", clock);

    @Test
    void refreshUsesRemainingLifetimeAndExactSecureCookieContract() {
        when(csrf.isValid("csrf")).thenReturn(true);
        UUID userId = UUID.randomUUID();
        var session = new RefreshSessionService.IssuedRefreshSession(
                UUID.randomUUID(), userId, List.of("CUSTOMER"), "new-token", clock.instant().plus(Duration.ofDays(7)));
        var user = new OnboardingService.UserSummary(userId, UUID.randomUUID(), "Customer", "0912345678",
                "customer@example.test", List.of("CUSTOMER"), true);
        when(onboarding.refreshSession("old-token")).thenReturn(new OnboardingService.RefreshResult(session, user));
        when(accessTokens.issue(userId, List.of("CUSTOMER"))).thenReturn("access-token");
        MockHttpServletRequest request = secureRequest("/api/v1/auth/refresh", "old-token", "csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.refresh(request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new SessionController.RefreshResponse("access-token", "Bearer", 900, user));
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=new-token", "Max-Age=604800",
                "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
    }

    @Test
    void refreshRestoresStaffRolesWithoutCustomerProfile() {
        when(csrf.isValid("csrf")).thenReturn(true);
        UUID userId = UUID.randomUUID();
        var session = new RefreshSessionService.IssuedRefreshSession(UUID.randomUUID(), userId,
                List.of("AUDITOR", "OPERATOR"), "new-token", clock.instant().plus(Duration.ofDays(7)));
        var user = new OnboardingService.UserSummary(userId, userId, "0912345678", "0912345678",
                "staff@example.test", List.of("AUDITOR", "OPERATOR"), false);
        when(onboarding.refreshSession("old-token")).thenReturn(new OnboardingService.RefreshResult(session, user));
        when(accessTokens.issue(userId, session.roles())).thenReturn("staff-access-token");

        var result = controller.refresh(secureRequest("/api/v1/auth/refresh", "old-token", "csrf"),
                new MockHttpServletResponse());

        assertThat(result.getBody()).isEqualTo(new SessionController.RefreshResponse(
                "staff-access-token", "Bearer", 900, user));
    }

    @Test
    void refreshSerializesLoginCompatibleUserShape() throws Exception {
        when(csrf.isValid("csrf")).thenReturn(true);
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var session = new RefreshSessionService.IssuedRefreshSession(UUID.randomUUID(), userId,
                List.of("CUSTOMER"), "new-token", clock.instant().plus(Duration.ofDays(7)));
        var user = new OnboardingService.UserSummary(userId, customerId, "Customer", "0912345678",
                "customer@example.test", List.of("CUSTOMER"), true);
        when(onboarding.refreshSession("old-token")).thenReturn(new OnboardingService.RefreshResult(session, user));
        when(accessTokens.issue(userId, session.roles())).thenReturn("access-token");

        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", "old-token"))
                        .header("X-CSRF-Token", "csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.user.userId").value(userId.toString()))
                .andExpect(jsonPath("$.user.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.user.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.isPinSet").value(true));
    }

    @Test
    void refreshWithoutCookieReturnsProblemUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setContextPath("/api/v1");
        request.addHeader("X-CSRF-Token", "csrf");
        when(csrf.isValid("csrf")).thenReturn(true);

        var result = controller.refresh(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertProblem((ResponseEntity<?>) result, 401, "SESSION_EXPIRED", "/api/v1/auth/refresh");
        verifyNoInteractions(sessions, onboarding);
    }

    @Test
    void refreshWithInvalidCsrfReturnsProblemForbiddenWithoutRotating() {
        MockHttpServletRequest request = secureRequest("/api/v1/auth/refresh", "old-token", "bad");
        when(csrf.isValid("bad")).thenReturn(false);
        var result = controller.refresh(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertProblem((ResponseEntity<?>) result, 403, "FORBIDDEN", "/api/v1/auth/refresh");
        verifyNoInteractions(sessions, onboarding);
    }

    @Test
    void refreshRejectedByServiceDoesNotSetCookieOrIssueAccessToken() {
        when(csrf.isValid("csrf")).thenReturn(true);
        when(onboarding.refreshSession("old-token")).thenThrow(new IllegalArgumentException("Refresh session is invalid"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.refresh(secureRequest("/api/v1/auth/refresh", "old-token", "csrf"), response);

        assertProblem((ResponseEntity<?>) result, 401, "SESSION_EXPIRED", "/api/v1/auth/refresh");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verifyNoInteractions(accessTokens);
    }

    @Test
    void logoutRevokesAndClearsCookieWithSecureFlag() {
        when(csrf.isValid("csrf")).thenReturn(true);
        MockHttpServletRequest request = secureRequest("/api/v1/auth/logout", "old-token", "csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.logout(request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(204);
        verify(sessions).revoke("old-token");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=", "Max-Age=0",
                "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
    }

    @Test
    void logoutWithInvalidCsrfReturnsProblemForbidden() {
        MockHttpServletRequest request = secureRequest("/api/v1/auth/logout", "old-token", "bad");
        when(csrf.isValid("bad")).thenReturn(false);
        var result = controller.logout(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertProblem((ResponseEntity<?>) result, 403, "FORBIDDEN", "/api/v1/auth/logout");
        verifyNoInteractions(sessions);
    }

    private MockHttpServletRequest secureRequest(String uri, String token, String csrfValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/api/v1");
        request.setSecure(true);
        request.setCookies(new Cookie("refresh_token", token));
        request.addHeader("X-CSRF-Token", csrfValue);
        return request;
    }

    private void assertProblem(ResponseEntity<?> response, int status, String code, String instance) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/problem+json"));
        Object body = response.getBody();
        assertThat(body).isInstanceOf(Problem.class);
        Problem problem = (Problem) body;
        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.instance()).isEqualTo(instance);
        assertThat(problem.correlationId()).isNotNull();
    }
}
