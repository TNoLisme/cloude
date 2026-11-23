package com.simulatedbank.banking.identity.adapter.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import com.simulatedbank.banking.identity.application.ClientContext;
import com.simulatedbank.banking.identity.application.LoginCommand;
import com.simulatedbank.banking.identity.application.LoginResult;
import com.simulatedbank.banking.identity.application.LogoutService;
import com.simulatedbank.banking.identity.application.PasswordLoginService;
import com.simulatedbank.banking.identity.application.SessionViewService;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.springframework.http.CacheControl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-AUTH-01 `POST /api/v1/auth/login` (public) + API-AUTH-06 `POST /api/v1/auth/logout`
 * (authenticated qua `SessionAuthenticationFilter`) + API-AUTH-07 `GET /api/v1/auth/session`
 * (BE-ID-IMP-04: actor propagation + CSRF rotation, 07 §8.1.7). Response/error mapping theo
 * 07 §4.3; anti-enumeration: mọi failure credential dùng cùng code/detail (07 §8.1.1).
 */
@RestController
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final PasswordLoginService loginService;
    private final LogoutService logoutService;
    private final SessionViewService sessionViewService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final OtpChallengeService challenges;

    public AuthController(PasswordLoginService loginService, LogoutService logoutService,
            SessionViewService sessionViewService, AppProperties properties, ObjectMapper objectMapper,
            OtpChallengeService challenges) {
        this.loginService = loginService;
        this.logoutService = logoutService;
        this.sessionViewService = sessionViewService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.challenges = challenges;
    }

    @PostMapping("/login")
    public ResponseEntity<ChallengeRequiredResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servlet) {
        LoginCommand command = new LoginCommand(request.username(), request.password().toCharArray(),
                request.deviceRef(), request.deviceName(), request.deviceFingerprint());
        ClientContext client = clientContext(servlet);
        var challenge = loginService.login(command, client);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ChallengeRequiredResponse.login(challenge, client.correlationId()));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verifyLogin(@RequestBody LoginOtpVerificationRequest request,
            HttpServletRequest servlet) {
        try {
            IdentityRequestValidation.requireUuid(request.challengeId());
            char[] otp = request.otp();
            IdentityRequestValidation.requireDigits(otp);
            ClientContext client = clientContext(servlet);
            LoginResult result = challenges.verifyLoginChallenge(request.challengeId(), otp, client);
            return AuthHttpSupport.authenticated(result, client.correlationId(), properties, false);
        } finally {
            request.eraseOtp();
        }
    }

    @PostMapping("/challenges/{challengeId}/resend")
    public ResponseEntity<ChallengeRequiredResponse> resend(@PathVariable String challengeId,
            @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body, HttpServletRequest servlet) {
        IdentityRequestValidation.requireUuid(challengeId);
        if (body != null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, "Yêu cầu gửi lại OTP không có body.");
        }
        Object snapshot = servlet.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        Long actor = snapshot instanceof com.simulatedbank.banking.common.config.SessionAuthenticator.Session session
                ? Long.valueOf(session.userId()) : null;
        String correlation = correlationId(servlet);
        var challenge = challenges.resend(challengeId, actor, correlation);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ChallengeRequiredResponse.login(challenge, correlation));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest servlet) {
        // Thiếu session → 401 AUTH_SESSION_EXPIRED (AC-004 fail-closed), trước khi đụng token;
        // không bao giờ rơi vào 500 vì credential vắng mặt.
        long actorUserId = Long.parseLong(actorUserId(servlet));
        String token = extractSessionToken(properties.security().cookie().name(), servlet);
        logoutService.logout(token, actorUserId, correlationId(servlet));
        ResponseCookie cleared = sessionCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    /**
     * API-AUTH-07 (07 §8.1.7, BE-ID-IMP-04): actor context hiện tại từ snapshot phiên tin cậy —
     * KHÔNG nhận bất kỳ identifier/role/scope nào từ client. Cấp lại CSRF token (rotation,
     * DEC-ID-004) trong một UPDATE fail-closed. Thiếu session → 401 fail-closed như logout.
     */
    @GetMapping("/session")
    public ResponseEntity<AuthResponse> session(HttpServletRequest servlet) {
        long actorUserId = Long.parseLong(actorUserId(servlet));
        String token = extractSessionToken(properties.security().cookie().name(), servlet);
        var snapshot = sessionSnapshot(servlet);
        var view = sessionViewService.view(snapshot, token);
        if (view.userId() != actorUserId) {
            // Bất biến: response không bao giờ vượt ra ngoài actor đã xác thực (anti-IDOR).
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AuthResponse(
                new AuthResponse.UserSummary(Long.toString(view.userId()), view.userType(),
                        view.username(), List.copyOf(view.roles()), List.copyOf(view.privileges()),
                        view.customerId()),
                new AuthResponse.SessionInfo(view.idleExpiresAt(), view.absoluteExpiresAt(),
                        view.rawCsrfToken(), false),
                correlationId(servlet)));
    }

    /** Principal từ `SessionAuthenticationFilter` (attribute session snapshot). */
    private static com.simulatedbank.banking.common.config.SessionAuthenticator.Session sessionSnapshot(
            HttpServletRequest request) {
        Object session = request.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        if (session instanceof com.simulatedbank.banking.common.config.SessionAuthenticator.Session snapshot) {
            return snapshot;
        }
        throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_SESSION_EXPIRED);
    }

    /** Thu credential duy nhất như filter; không log token. */
    private static String extractSessionToken(String cookieName, HttpServletRequest request) {
        var cookies = request.getCookies() == null ? List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(request.getCookies()).toList();
        var cookieToken = cookies.stream()
                .filter(cookie -> cookie.getName().equals(cookieName))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst();
        var bearerToken = java.util.Collections.list(request.getHeaders("Authorization")).stream()
                .filter(value -> value.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(value -> value.substring(7))
                .findFirst();
        return cookieToken.or(() -> bearerToken).orElseThrow();
    }

    /** Principal từ `SessionAuthenticationFilter` (attribute session snapshot). */
    private static String actorUserId(HttpServletRequest request) {
        return sessionSnapshot(request).userId();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        var cookie = properties.security().cookie();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .sameSite(cookie.sameSite())
                .path(cookie.path())
                .maxAge(maxAge);
        return builder.build();
    }

    private ClientContext clientContext(HttpServletRequest request) {
        String maskedIp = PiiMaskingUtils.maskIp(request.getRemoteAddr());
        String userAgent = request.getHeader("User-Agent");
        String maskedUserAgent = userAgent == null ? null
                : userAgent.length() <= 128 ? userAgent : userAgent.substring(0, 128);
        String clientInfoJson = toJson(Map.of("ip", maskedIp == null ? "" : maskedIp,
                "ua", maskedUserAgent == null ? "" : maskedUserAgent));
        return new ClientContext(correlationId(request), maskedIp, maskedUserAgent, clientInfoJson);
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception failure) {
            return "{}";
        }
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute("correlationId");
        return value instanceof String text && !text.isEmpty() ? text
                : UUID.randomUUID().toString();
    }
}
