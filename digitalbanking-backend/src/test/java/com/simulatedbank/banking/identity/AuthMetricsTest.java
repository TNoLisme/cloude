package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.identity.application.AuthMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** BE-ID-IMP-13 — metrics xác thực: label bounded, không secret, hạ tầng metrics lỗi không lan. */
class AuthMetricsTest {

    @Test
    void loginAttemptsUseBoundedLabelsAndNeverSecrets() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AuthMetrics(registry);

        metrics.loginAttempt("PASSWORD", true, "NONE");
        metrics.loginAttempt("PASSWORD", false, "AUTH_INVALID_CREDENTIALS");
        metrics.loginAttempt("FIDO", false, "AUTH_ACCOUNT_LOCKED");
        metrics.loginDuration("PASSWORD", 1_500_000L);
        metrics.mfaVerify("LOGIN", true, "NONE");
        metrics.mfaVerify("STEP_UP", false, "AUTH_OTP_INVALID");
        metrics.sessionCreated("OTP");
        metrics.sessionTerminated("REVOKED");
        metrics.loginRateLimited();

        assertThat(registry.counter("auth.login.requests",
                "method", "PASSWORD", "result", "SUCCESS", "reason", "NONE").count()).isEqualTo(1.0);
        assertThat(registry.counter("auth.login.requests",
                "method", "PASSWORD", "result", "FAILURE", "reason", "AUTH_INVALID_CREDENTIALS").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("auth.mfa.verify",
                "purpose", "STEP_UP", "result", "FAILURE", "reason", "AUTH_OTP_INVALID").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("auth.session.created", "method", "OTP").count()).isEqualTo(1.0);
        assertThat(registry.counter("auth.session.terminated", "outcome", "REVOKED").count()).isEqualTo(1.0);
        assertThat(registry.counter("auth.login.rate_limited", "group", "login-username").count())
                .isEqualTo(1.0);
        var timer = registry.timer("auth.login.duration", "method", "PASSWORD");
        assertThat(timer.count()).isEqualTo(1);
    }

    private static final java.util.List<String> BOUNDED_REASONS = java.util.List.of(
            "NONE", "AUTH_INVALID_CREDENTIALS", "AUTH_ACCOUNT_LOCKED", "AUTH_FORBIDDEN", "RATE_LIMITED",
            "AUTH_DEVICE_NOT_TRUSTED", "AUTH_OTP_INVALID", "AUTH_OTP_EXPIRED", "AUTH_CHALLENGE_LOCKED",
            "AUTH_CHALLENGE_NOT_FOUND", "AUTH_CHALLENGE_EXPIRED", "STEP_UP_CHALLENGE_MISMATCH",
            "INVALID_STATE_TRANSITION", "OTP_DISPATCH_FAILED", "STEP_UP_REQUIRED", "VALIDATION_ERROR",
            "SERVICE_UNAVAILABLE", "INTERNAL_ERROR");

    @Test
    void reasonLabelsStayWithinTheClosedErrorCodeCatalog() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AuthMetrics(registry);
        for (String reason : BOUNDED_REASONS) {
            metrics.loginAttempt("PASSWORD", false, reason);
        }
        assertThat(registry.getMeters().stream().filter(
                meter -> meter.getId().getName().equals("auth.login.requests")).count())
                .isEqualTo(BOUNDED_REASONS.size());
    }

    @Test
    void metricsInfrastructureFailureNeverPropagates() {
        var broken = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("metrics down"));
        when(broken.timer(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("metrics down"));
        var metrics = new AuthMetrics(broken);

        assertThatCode(() -> {
            metrics.loginAttempt("PASSWORD", true, "NONE");
            metrics.loginDuration("PASSWORD", 1L);
            metrics.mfaVerify("LOGIN", false, "AUTH_OTP_INVALID");
            metrics.sessionCreated("OTP");
            metrics.sessionTerminated("IDEMPOTENT");
            metrics.loginRateLimited();
        }).doesNotThrowAnyException();
    }
}
