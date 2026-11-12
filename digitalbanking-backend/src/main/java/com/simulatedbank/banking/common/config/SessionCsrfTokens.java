package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.crypto.HmacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** Native CsrfFilter compares the header's SHA-256 against the identity-owned session hash. */
final class SessionCsrfTokens implements CsrfTokenRepository, CsrfTokenRequestHandler {
    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        var session = (SessionAuthenticator.Session) request.getAttribute(SessionAuthenticationFilter.SESSION);
        return session == null ? null : new DefaultCsrfToken("X-CSRF-Token", "_csrf", session.csrfTokenHash());
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        throw new IllegalStateException("Identity must supply session-bound CSRF state");
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        throw new IllegalStateException("Identity owns CSRF token lifecycle");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        // Identity returns the raw token via auth responses; do not expose the stored hash as an attribute.
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        var values = Collections.list(request.getHeaders("X-CSRF-Token"));
        return values.size() == 1 && !values.getFirst().isEmpty() && values.getFirst().length() <= 512
                ? HmacService.sha256Hex(values.getFirst()) : null;
    }
}
