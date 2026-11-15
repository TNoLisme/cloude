package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.IdentityService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identity;
    private final AppProperties properties;

    public AuthController(IdentityService identity, AppProperties properties) {
        this.identity = identity;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        IdentityService.LoginResult result = identity.login(new IdentityService.LoginCommand(request.username(),
                request.password(), request.deviceRef(), request.deviceName(), request.deviceFingerprint()));
        if (!result.authenticated()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ChallengeRequiredResponse("AUTH_CHALLENGE_REQUIRED",
                            "Additional authentication is required.", result.challenge(), correlationId()));
        }
        return authenticated(result.session());
    }

    @PostMapping("/passcode")
    public ResponseEntity<AuthResponse> loginPasscode(@Valid @RequestBody PasscodeRequest request) {
        return authenticated(identity.loginPasscode(new IdentityService.PasscodeCommand(request.username(),
                request.passcode(), request.deviceRef(), request.deviceFingerprint())));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verifyLoginOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return authenticated(identity.verifyLoginOtp(request.challengeId(), request.otp()));
    }

    @PostMapping("/step-up/verify")
    public StepUpResponse verifyStepUp(Authentication authentication, @Valid @RequestBody StepUpRequest request) {
        long userId = currentUserId(authentication);
        IdentityService.StepUpVerification result = identity.verifyStepUp(userId, request.challengeId(),
                request.method(), request.otp());
        return new StepUpResponse(result.challengeId(), "VERIFIED", result.purpose(), result.verifiedAt(),
                result.expiresAt(), correlationId());
    }

    @PostMapping("/challenges/{challengeId}/resend")
    public ResponseEntity<ChallengeRequiredResponse> resend(@PathVariable UUID challengeId,
            Authentication authentication) {
        Long userId = authentication == null ? null : currentUserId(authentication);
        StepUpChallengePort.Challenge challenge = identity.resend(challengeId, userId);
        return ResponseEntity.accepted().body(new ChallengeRequiredResponse("AUTH_CHALLENGE_REQUIRED",
                "Additional authentication is required.", challenge, correlationId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        identity.logout(opaqueToken(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @GetMapping("/session")
    public ResponseEntity<AuthResponse> session(HttpServletRequest request) {
        return authenticated(identity.rotateCsrfAndReadSession(opaqueToken(request)));
    }

    private ResponseEntity<AuthResponse> authenticated(IdentityService.SessionGrant grant) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(grant.rawToken()).toString())
                .body(toResponse(grant));
    }

    private AuthResponse toResponse(IdentityService.SessionGrant grant) {
        UserSummary user = new UserSummary(Long.toString(grant.userId()), grant.userType(), grant.displayName(),
                grant.roles(), grant.privileges(), grant.customerId() == null ? null : Long.toString(grant.customerId()));
        SessionInfo session = new SessionInfo(grant.idleExpiresAt(), grant.absoluteExpiresAt(), grant.csrfToken(),
                grant.deviceTrusted());
        return new AuthResponse(user, session, correlationId());
    }

    private ResponseCookie sessionCookie(String token) {
        AppProperties.Security.Cookie config = properties.security().cookie();
        return ResponseCookie.from(config.name(), token).httpOnly(true).secure(config.secure())
                .sameSite(config.sameSite()).path(config.path()).maxAge(Duration.ofSeconds(config.maxAgeSeconds()))
                .build();
    }

    private ResponseCookie clearCookie() {
        AppProperties.Security.Cookie config = properties.security().cookie();
        return ResponseCookie.from(config.name(), "").httpOnly(true).secure(config.secure())
                .sameSite(config.sameSite()).path(config.path()).maxAge(Duration.ZERO).build();
    }

    private String opaqueToken(HttpServletRequest request) {
        List<String> authorization = java.util.Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        String cookieName = properties.security().cookie().name();
        List<Cookie> cookies = request.getCookies() == null ? List.of() : Arrays.stream(request.getCookies())
                .filter(cookie -> cookieName.equals(cookie.getName())).toList();
        if (authorization.size() + cookies.size() != 1) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        if (!authorization.isEmpty()) {
            String value = authorization.getFirst();
            if (!value.startsWith("Bearer ") || value.length() <= 7) {
                throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
            }
            return value.substring(7);
        }
        return cookies.getFirst().getValue();
    }

    private static long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return Long.parseLong(session.userId());
    }

    private static String correlationId() {
        return RequestContextHolder.current().map(context -> context.correlationId()).orElse("internal");
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotNull @Size(min = 1, max = 128) String password,
            UUID deviceRef,
            @Size(min = 1, max = 100) String deviceName,
            @Size(min = 1, max = 512) String deviceFingerprint) {
    }

    public record PasscodeRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Pattern(regexp = "^[0-9]{6,8}$") String passcode,
            @NotNull UUID deviceRef,
            @NotBlank @Size(max = 512) String deviceFingerprint) {
    }

    public record OtpVerifyRequest(@NotNull UUID challengeId,
            @NotBlank @Pattern(regexp = "^[0-9]{6,8}$") String otp) {
    }

    public record StepUpRequest(@NotNull UUID challengeId,
            @NotBlank @Pattern(regexp = "^(OTP|PASSCODE|FIDO)$") String method,
            @NotBlank @Pattern(regexp = "^[0-9]{6,8}$") String otp) {
    }

    public record ChallengeRequiredResponse(String code, String message, StepUpChallengePort.Challenge challenge,
            String correlationId) {
    }

    public record UserSummary(String userId, String userType, String displayName, List<String> roles,
            List<String> privileges, String customerId) {
    }

    public record SessionInfo(Instant idleExpiresAt, Instant absoluteExpiresAt, String csrfToken,
            boolean deviceTrusted) {
    }

    public record AuthResponse(UserSummary user, SessionInfo session, String correlationId) {
    }

    public record StepUpResponse(UUID challengeId, String status, StepUpChallengePort.Purpose purpose,
            Instant verifiedAt, Instant expiresAt, String correlationId) {
    }
}
