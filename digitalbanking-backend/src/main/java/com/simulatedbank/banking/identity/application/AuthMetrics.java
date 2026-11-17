package com.simulatedbank.banking.identity.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BE-ID-IMP-13 — Metrics xác thực Identity trên hạ tầng Common (Micrometer; BE-OBS-18:
 * KHÔNG dùng `userId`, `correlationId`, username, reference hay giá trị secret làm label).
 *
 * <p>Label chỉ gồm giá trị bounded: method (`PASSWORD`/`FIDO`/`PASSCODE`/`OTP`), purpose
 * (`LOGIN`/`STEP_UP`), result (`SUCCESS`/`FAILURE`), reason = tên `ErrorCode` (catalog 07 §4.3,
 * đóng) và outcome phiên. Không bao giờ chứa password/OTP/token/session id.</p>
 *
 * <p>Failure policy: metrics là telemetry best-effort — lỗi hạ tầng metrics KHÔNG được ảnh
 * hưởng xác thực (fail-open, log warn); khác với audit C2/C3 theo hợp đồng riêng.</p>
 */
@Component
public class AuthMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AuthMetrics.class);

    private final MeterRegistry registry;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Đếm lần thử xác thực (primary). `reason` = mã lỗi catalog, `NONE` khi thành công. */
    public void loginAttempt(String method, boolean success, String reason) {
        count("auth.login.requests", "method", method, "result", success ? "SUCCESS" : "FAILURE",
                "reason", success ? "NONE" : reason);
    }

    /** Thời gian xử lý đăng nhập/MFA verify trong application service (nanoseconds). */
    public void loginDuration(String method, long nanos) {
        safe("auth.login.duration", () -> registry
                .timer("auth.login.duration", "method", method)
                .record(nanos, TimeUnit.NANOSECONDS));
    }

    /** MFA/OTP verify attempt: purpose `LOGIN`/`STEP_UP`, reason = mã lỗi, `NONE` khi thành công. */
    public void mfaVerify(String purpose, boolean success, String reason) {
        count("auth.mfa.verify", "purpose", purpose, "result", success ? "SUCCESS" : "FAILURE",
                "reason", success ? "NONE" : reason);
    }

    /** Session tạo thành công trong TX xác thực (method = `OTP`/`FIDO`). */
    public void sessionCreated(String method) {
        count("auth.session.created", "method", method);
    }

    /** Session terminated: `REVOKED` khi logout chuyển trạng thái, `IDEMPOTENT` khi không còn ACTIVE. */
    public void sessionTerminated(String outcome) {
        count("auth.session.terminated", "outcome", outcome);
    }

    /** Rejected bởi username quota Identity (bucket `login-username`); IP/global do common filter đếm. */
    public void loginRateLimited() {
        count("auth.login.rate_limited", "group", "login-username");
    }

    private void count(String name, String... tags) {
        safe(name, () -> registry.counter(name, tags).increment());
    }

    private void safe(String name, Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException metricsFailure) {
            // Metrics không được làm hỏng xác thực; chỉ telemetry bị mất.
            LOG.warn("METRIC_WRITE_FAILED name={}", name);
        }
    }
}
