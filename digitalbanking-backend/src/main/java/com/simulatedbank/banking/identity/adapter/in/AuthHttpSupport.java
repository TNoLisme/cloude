package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import com.simulatedbank.banking.identity.application.ClientContext;
import com.simulatedbank.banking.identity.application.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

/** One session-cookie/response contract for verified OTP and FIDO logins. */
final class AuthHttpSupport {
    private AuthHttpSupport() {}

    static ResponseEntity<AuthResponse> authenticated(LoginResult result, String correlationId,
            AppProperties properties, boolean deviceTrusted) {
        var cookie = properties.security().cookie();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookie.name(), result.rawSessionToken())
                        .httpOnly(true).secure(cookie.secure()).sameSite(cookie.sameSite()).path(cookie.path())
                        .maxAge(Duration.ofSeconds(cookie.maxAgeSeconds())).build().toString())
                .body(new AuthResponse(new AuthResponse.UserSummary(Long.toString(result.userId()), result.userType(),
                        result.username(), result.roles(), publicV1Privileges(result.privileges()), result.customerId()),
                        new AuthResponse.SessionInfo(result.idleExpiresAt(), result.absoluteExpiresAt(),
                                result.rawCsrfToken(), deviceTrusted), correlationId));
    }

    /** UserSummary.privileges is PrivilegeCode=[CHECKER], not the internal permission authority set. */
    static List<String> publicV1Privileges(Set<String> authorities) {
        return authorities.contains("CHECKER") ? List.of("CHECKER") : List.of();
    }

    static ClientContext fidoClient(HttpServletRequest request) {
        Object trace = request.getAttribute("correlationId");
        String correlationId = trace instanceof String text ? text : UUID.randomUUID().toString();
        // Untrusted User-Agent is not needed for verification or security audit.
        return new ClientContext(correlationId, PiiMaskingUtils.maskIp(request.getRemoteAddr()), null, "{}");
    }
}
