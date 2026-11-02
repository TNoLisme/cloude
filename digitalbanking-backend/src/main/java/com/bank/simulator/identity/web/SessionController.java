package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.infrastructure.security.CsrfTokenService;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.correlation.CorrelationIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
public class SessionController {

    private final RefreshSessionService refreshSessionService;
    private final OnboardingService onboardingService;
    private final CsrfTokenService csrfTokenService;
    private final JwtAccessTokenCodec accessTokenCodec;
    private final String cookieName;
    private final Clock clock;

    public SessionController(RefreshSessionService refreshSessionService, OnboardingService onboardingService,
                             CsrfTokenService csrfTokenService,
                             JwtAccessTokenCodec accessTokenCodec,
                             @Value("${app.security.refresh-cookie-name:refresh_token}") String cookieName,
                             Clock clock) {
        this.refreshSessionService = refreshSessionService;
        this.onboardingService = onboardingService;
        this.csrfTokenService = csrfTokenService;
        this.accessTokenCodec = accessTokenCodec;
        this.cookieName = cookieName;
        this.clock = clock;
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = cookie(request);
        if (rawToken == null) return problem(401, "SESSION_EXPIRED", request);
        if (!csrfTokenService.isValid(request.getHeader("X-CSRF-Token"))) {
            return problem(403, "FORBIDDEN", request);
        }
        try {
            OnboardingService.RefreshResult refreshed = onboardingService.refreshSession(rawToken);
            RefreshSessionService.IssuedRefreshSession session = refreshed.session();
            response.addHeader("Set-Cookie", cookieHeader(session.rawToken(), remainingSeconds(session.expiresAt()), request.isSecure()));
            String accessToken = accessTokenCodec.issue(session.userId(), session.roles());
            return ResponseEntity.ok(new RefreshResponse(accessToken, "Bearer", 900, refreshed.user()));
        } catch (IllegalArgumentException exception) {
            return problem(401, "SESSION_EXPIRED", request);
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = cookie(request);
        if (rawToken == null) return problem(401, "SESSION_EXPIRED", request);
        if (!csrfTokenService.isValid(request.getHeader("X-CSRF-Token"))) {
            return problem(403, "FORBIDDEN", request);
        }
        refreshSessionService.revoke(rawToken);
        response.addHeader("Set-Cookie", cookieHeader("", 0, request.isSecure()));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Problem> problem(int status, String code, HttpServletRequest request) {
        UUID correlationId = CorrelationIdFilter.from(request);
        if (correlationId == null) correlationId = UUID.randomUUID();
        Problem body = new Problem(URI.create("about:blank"), status == 401 ? "Unauthorized" : "Forbidden",
                status, status == 401 ? "Authentication is required." : "Request is forbidden.",
                request.getRequestURI(), code, correlationId, null);
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/problem+json")).body(body);
    }

    private long remainingSeconds(Instant expiresAt) {
        return Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String cookieHeader(String value, long maxAge, boolean secure) {
        return cookieName + "=" + value + "; Max-Age=" + maxAge
                + "; Path=/api/v1/auth; HttpOnly" + (secure ? "; Secure" : "") + "; SameSite=Lax";
    }

    public record RefreshResponse(String accessToken, String tokenType, int expiresIn,
                                  OnboardingService.UserSummary user) {
    }
}
