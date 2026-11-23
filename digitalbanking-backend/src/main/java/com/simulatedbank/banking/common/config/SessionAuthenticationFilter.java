package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionAuthenticationFilter extends OncePerRequestFilter {
    static final String SESSION = SessionAuthenticationFilter.class.getName() + ".session";
    static final String COOKIE_AUTH = SessionAuthenticationFilter.class.getName() + ".cookie";
    private final ObjectProvider<SessionAuthenticator> authenticators;
    private final ProblemWriter writer;

    SessionAuthenticationFilter(ObjectProvider<SessionAuthenticator> authenticators, ProblemWriter writer) {
        this.authenticators = authenticators;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        var cookies = request.getCookies() == null ? List.<Cookie>of() : Arrays.stream(request.getCookies())
                .filter(cookie -> cookie.getName().equals("DB_SESSION")).toList();
        if (headers.isEmpty() && cookies.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        // Reject ambiguous credentials; never fall back from a failed Bearer to ambient cookies.
        if (headers.size() > 1 || cookies.size() > 1 || (!headers.isEmpty() && !cookies.isEmpty())) {
            reject(request, response);
            return;
        }
        boolean cookieAuth = headers.isEmpty();
        String value = cookieAuth ? cookies.getFirst().getValue() : headers.getFirst();
        if (!cookieAuth) {
            if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                reject(request, response);
                return;
            }
            value = value.substring(7);
        }
        if (value.isEmpty() || value.length() > 512 || !value.matches("[A-Za-z0-9._~+/=-]+")) {
            reject(request, response);
            return;
        }
        SessionAuthenticator authenticator = authenticators.getIfAvailable();
        if (authenticator == null) {
            if (cookieAuth && isPublicAuthentication(request)) {
                chain.doFilter(request, response);
            } else {
                reject(request, response);
            }
            return;
        }
        SessionAuthenticator.Session session;
        try {
            session = authenticator.authenticate(value).orElse(null);
        } catch (BankingBusinessException failure) {
            if (cookieAuth && isPublicAuthentication(request) && failure.code() == ErrorCode.AUTH_SESSION_EXPIRED) {
                chain.doFilter(request, response);
                return;
            }
            writer.write(response, writer.create(failure.code(), failure.safeDetail(), request,
                    List.of(), failure.extensions()));
            return;
        }
        if (session == null) {
            // An expired ambient HttpOnly cookie must not prevent a new public login.
            if (cookieAuth && isPublicAuthentication(request)) {
                chain.doFilter(request, response);
            } else {
                reject(request, response);
            }
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(session, null,
                session.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        request.setAttribute(com.simulatedbank.banking.common.context.HttpRequestLog.USER_ATTRIBUTE,
                com.simulatedbank.banking.common.context.HttpRequestLog.maskedUserId(session.userId()));
        request.setAttribute(SESSION, session);
        request.setAttribute(COOKIE_AUTH, cookieAuth);
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(SESSION);
            request.removeAttribute(COOKIE_AUTH);
        }
    }

    private static boolean isPublicAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return request.getMethod().equals("POST") && (List.of("/api/v1/auth/login", "/api/v1/auth/passcode",
                "/api/v1/auth/fido/verify", "/api/v1/auth/otp/verify").contains(path)
                || path.matches("/api/v1/auth/challenges/[^/]+/resend"));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writer.write(response, writer.create(ErrorCode.AUTH_SESSION_EXPIRED,
                ErrorCode.AUTH_SESSION_EXPIRED.detail(), request, List.of(), Map.of()));
    }
}
