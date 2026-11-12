package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

final class RequestRateLimitFilter extends OncePerRequestFilter {
    private final RequestRateLimiter limiter;
    private final AppProperties.Security.RateLimit limits;
    private final ProblemWriter writer;

    RequestRateLimitFilter(RequestRateLimiter limiter, AppProperties properties, ProblemWriter writer) {
        this.limiter = limiter;
        this.limits = properties.security().rateLimit();
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/v1/") || request.getMethod().equals("OPTIONS")) {
            chain.doFilter(request, response);
            return;
        }
        var session = (SessionAuthenticator.Session) request.getAttribute(SessionAuthenticationFilter.SESSION);
        try {
            String subject = session == null ? "ip:" + request.getRemoteAddr() : "user:" + session.userId();
            long retry = limiter.acquire("global", subject, limits.readPerUserPerMinute(), Duration.ofMinutes(1));
            if (retry == 0 && request.getMethod().equals("POST")) {
                if (path.equals("/api/v1/auth/login")) {
                    retry = limiter.acquire("login-ip", request.getRemoteAddr(), limits.loginPerIpPerMinute(), Duration.ofMinutes(1));
                } else if (session != null && path.equals("/api/v1/transfers")) {
                    if (session.customerId() == null) {
                        fail(request, response, ErrorCode.AUTH_FORBIDDEN, null);
                        return;
                    }
                    retry = limiter.acquire("transfer-customer", session.customerId(),
                            limits.transferPerCustomerPerMinute(), Duration.ofMinutes(1));
                } else if (session != null && path.equals("/api/v1/auth/step-up/verify")) {
                    retry = limiter.acquire("step-up", session.userId(), limits.stepUpPerUserPer5Minutes(), Duration.ofMinutes(5));
                } else if (session != null && path.equals("/api/v1/auth/otp/verify")) {
                    retry = limiter.acquire("otp-user", session.userId(), limits.otpVerifyPerUserPerMinute(), Duration.ofMinutes(1));
                }
            } else if (retry == 0 && session != null && request.getMethod().equals("GET")
                    && path.equals("/api/v1/audit/export")) {
                retry = limiter.acquire("audit-export", session.userId(), limits.auditExportPerUserPerHour(), Duration.ofHours(1));
            }
            if (retry != 0) {
                fail(request, response, ErrorCode.RATE_LIMITED, retry);
                return;
            }
        } catch (RequestRateLimiter.CapacityExceededException ex) {
            fail(request, response, ErrorCode.SERVICE_UNAVAILABLE, null);
            return;
        }
        chain.doFilter(request, response);
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, ErrorCode code, Long retry)
            throws IOException {
        writer.write(response, writer.create(code, code.detail(), request, List.of(), Map.of()), retry);
    }
}
