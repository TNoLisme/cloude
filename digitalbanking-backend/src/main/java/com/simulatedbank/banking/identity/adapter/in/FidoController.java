package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.config.RequestRateLimiter;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import com.simulatedbank.banking.identity.application.FidoLoginService;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-AUTH-03 begin and finish; no fallback shortcut, token or authentication state on 202/denial. */
@RestController
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class FidoController {
    private static final String UUID_PATTERN = IdentityRequestValidation.UUID_REGEX;
    private final FidoLoginService service;
    private final AppProperties properties;
    private final RequestRateLimiter limiter;
    private final ProblemWriter writer;

    public FidoController(FidoLoginService service, AppProperties properties, RequestRateLimiter limiter,
            ProblemWriter writer) {
        this.service = service;
        this.properties = properties;
        this.limiter = limiter;
        this.writer = writer;
    }

    public record Request(@NotNull @Size(min = 1, max = 64)
            @JsonDeserialize(using = StrictStringDeserializer.class) String username,
            @NotBlank @Pattern(regexp = UUID_PATTERN)
            @JsonDeserialize(using = StrictStringDeserializer.class) String deviceRef,
            @Pattern(regexp = UUID_PATTERN)
            @JsonDeserialize(using = StrictStringDeserializer.class) String challengeId, @Valid Assertion fido) {
        @Override public String toString() { return "FidoRequest[redacted]"; }
    }

    public record Assertion(@NotBlank @Pattern(regexp = IdentityRequestValidation.BASE64URL_REGEX)
            @JsonDeserialize(using = StrictStringDeserializer.class) String credentialId,
            @NotBlank @Pattern(regexp = IdentityRequestValidation.BASE64URL_REGEX)
            @JsonDeserialize(using = StrictStringDeserializer.class) String clientDataJSON,
            @NotBlank @Pattern(regexp = IdentityRequestValidation.BASE64URL_REGEX)
            @JsonDeserialize(using = StrictStringDeserializer.class) String authenticatorData,
            @NotBlank @Pattern(regexp = IdentityRequestValidation.BASE64URL_REGEX)
            @JsonDeserialize(using = StrictStringDeserializer.class) String signature,
            @Pattern(regexp = IdentityRequestValidation.BASE64URL_REGEX)
            @JsonDeserialize(using = StrictStringDeserializer.class) String userHandle) {
        FidoAssertionVerifier.Assertion toCommand() {
            return new FidoAssertionVerifier.Assertion(credentialId, clientDataJSON, authenticatorData, signature, userHandle);
        }
        void validate() {
            IdentityRequestValidation.requireBase64Url(credentialId);
            IdentityRequestValidation.requireBase64Url(clientDataJSON);
            IdentityRequestValidation.requireBase64Url(authenticatorData);
            IdentityRequestValidation.requireBase64Url(signature);
            if (userHandle != null) {
                IdentityRequestValidation.requireBase64Url(userHandle);
            }
        }
        @Override public String toString() { return "FidoAssertion[redacted]"; }
    }

    @PostMapping("/api/v1/auth/fido/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody Request body, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if ((body.fido() == null) != (body.challengeId() == null)) {
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var limits = properties.security().rateLimit();
        try {
            long wait = limiter.acquire("login-ip", request.getRemoteAddr(), limits.loginPerIpPerMinute(), Duration.ofMinutes(1));
            if (wait == 0) {
                wait = limiter.acquire("login-username", body.username().toLowerCase(Locale.ROOT),
                        limits.loginPerUsernamePerMinute(), Duration.ofMinutes(1));
            }
            if (wait != 0) {
                writer.write(response, writer.create(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.detail(),
                        request, List.of(), Map.of()), wait);
                return null;
            }
        } catch (RequestRateLimiter.CapacityExceededException full) {
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        var client = AuthHttpSupport.fidoClient(request);
        if (body.fido() == null) {
            var begun = service.begin(body.username(), java.util.UUID.fromString(body.deviceRef()).toString(), client);
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(ChallengeRequiredResponse.fido(begun, client.correlationId()));
        }
        var result = service.finish(body.username(), java.util.UUID.fromString(body.deviceRef()).toString(),
                java.util.UUID.fromString(body.challengeId()).toString(), body.fido().toCommand(), client);
        return AuthHttpSupport.authenticated(result, client.correlationId(), properties, true);
    }
}
