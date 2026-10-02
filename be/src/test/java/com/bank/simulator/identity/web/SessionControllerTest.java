package com.bank.simulator.identity.web;

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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionControllerTest {

    private final RefreshSessionService sessions = mock(RefreshSessionService.class);
    private final CsrfTokenService csrf = mock(CsrfTokenService.class);
    private final JwtAccessTokenCodec accessTokens = mock(JwtAccessTokenCodec.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
    private final SessionController controller = new SessionController(sessions, csrf, accessTokens, "refresh_token", clock);

    @Test
    void refreshUsesRemainingLifetimeAndExactSecureCookieContract() {
        when(csrf.isValid("csrf")).thenReturn(true);
        UUID userId = UUID.randomUUID();
        when(sessions.rotate("old-token")).thenReturn(new RefreshSessionService.IssuedRefreshSession(
                UUID.randomUUID(), userId, List.of("CUSTOMER"), "new-token", clock.instant().plus(Duration.ofDays(7))));
        when(accessTokens.issue(userId, List.of("CUSTOMER"))).thenReturn("access-token");
        MockHttpServletRequest request = secureRequest("/api/v1/auth/refresh", "old-token", "csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.refresh(request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new SessionController.AccessTokenResponse("access-token", "Bearer", 900));
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=new-token", "Max-Age=604800",
                "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
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
        verifyNoInteractions(sessions);
    }

    @Test
    void refreshWithInvalidCsrfReturnsProblemForbiddenWithoutRotating() {
        MockHttpServletRequest request = secureRequest("/api/v1/auth/refresh", "old-token", "bad");
        when(csrf.isValid("bad")).thenReturn(false);
        var result = controller.refresh(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertProblem((ResponseEntity<?>) result, 403, "FORBIDDEN", "/api/v1/auth/refresh");
        verifyNoInteractions(sessions);
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
