package com.simulatedbank.banking.identity.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-001 password login (API-AUTH-01). Luồng đã duyệt (07 §8.1.1; design/04 §4.1):
 * normalize username → lookup → anti-enumeration (unknown user vẫn chạy Argon2id dummy verify,
 * cùng `401 AUTH_INVALID_CREDENTIALS`) → kiểm tra state/lockout → verify Argon2id →
 * primary success: reset counter, bind device metadata, issue MFA challenge; NEVER create session.
 *
 * <p>Transaction boundary thuộc application (BE-CON-16/27). Verify mật khẩu (chi phí cao)
 * chạy ngoài transaction; các bước state-changing dùng `TransactionTemplate` OWN.
 * Audit C3 standalone best-effort; audit C2 cùng TX tạo session (fail-closed, design/01 §5.2).</p>
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class PasswordLoginService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordLoginService.class);

    private final UserAccountRepository users;
    private final DeviceRepository devices;
    private final OtpChallengeService challenges;
    private final com.simulatedbank.banking.common.crypto.HmacService hmac;
    private final AuthAuditPort audit;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final int maxFailedAttempts;
    private final int lockDurationMinutes;
    private final com.simulatedbank.banking.common.config.AppProperties.Security.RateLimit rateLimits;
    private final com.simulatedbank.banking.common.config.RequestRateLimiter limiter;
    private final AuthMetrics metrics;
    private final TransactionTemplate transactions;

    public PasswordLoginService(UserAccountRepository users, DeviceRepository devices,
            OtpChallengeService challenges, AuthAuditPort audit, PasswordHasher hasher, Clock clock,
            com.simulatedbank.banking.common.config.AppProperties properties,
            PlatformTransactionManager transactionManager,
            com.simulatedbank.banking.common.crypto.HmacService hmac,
            com.simulatedbank.banking.common.config.RequestRateLimiter limiter,
            AuthMetrics metrics) {
        this.users = users;
        this.devices = devices;
        this.challenges = challenges;
        this.hmac = hmac;
        this.audit = audit;
        this.hasher = hasher;
        this.clock = clock;
        this.maxFailedAttempts = properties.security().lockout().maxFailedAttempts();
        this.lockDurationMinutes = properties.security().lockout().lockDurationMinutes();
        this.rateLimits = properties.security().rateLimit();
        this.limiter = limiter;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Suy luận nội bộ dùng cho audit/reason code; không bao giờ lộ ra API. */
    enum FailureReason {
        UNKNOWN_USER, WRONG_PASSWORD, BAD_HASH, ACCOUNT_DISABLED, ACCOUNT_LOCKED
    }

    public com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView login(
            LoginCommand command, ClientContext client) {
        // 07 §3.1: normalize = lowercase; quota dùng đúng chuỗi đã normalize để không bypass qua casing.
        String username = command.username().toLowerCase(Locale.ROOT);
        long started = System.nanoTime();
        try {
            enforceUsernameQuota(username);
            var challenge = doLogin(username, command, client);
            metrics.loginAttempt("PASSWORD", true, "NONE");
            return challenge;
        } catch (com.simulatedbank.banking.common.error.BankingBusinessException rejection) {
            metrics.loginAttempt("PASSWORD", false, rejection.code().name());
            throw rejection;
        } catch (org.springframework.orm.jpa.JpaSystemException
                | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            // 07 §8.1.1 + 07 §4.3: DB không khả dụng → 503 retryable, fail-closed, không lộ internals.
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE);
        } finally {
            metrics.loginDuration("PASSWORD", System.nanoTime() - started);
            Arrays.fill(command.password(), '\0');
        }
    }

    /**
     * BE-ID-IMP-13 — quota theo username (07 §2.8, design/04 §8.3; DEC-05 của BE-COM-IMP-05):
     * gọi TRƯỚC khi verify credential; common không parse body login nên Identity tự normalize
     * và gọi shared limiter. Từ chối → `429 RATE_LIMITED` kèm `Retry-After` thật (giây).
     * Fail-closed: bucket đầy (`CapacityExceededException`) → 503 như common filter.
     */
    private void enforceUsernameQuota(String normalizedUsername) {
        long retry;
        try {
            retry = limiter.acquire("login-username", normalizedUsername,
                    rateLimits.loginPerUsernamePerMinute(), java.time.Duration.ofMinutes(1));
        } catch (com.simulatedbank.banking.common.config.RequestRateLimiter.CapacityExceededException capacity) {
            metrics.loginRateLimited();
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (retry != 0) {
            metrics.loginRateLimited();
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.RATE_LIMITED,
                    com.simulatedbank.banking.common.error.ErrorCode.RATE_LIMITED.detail(),
                    java.util.Map.of("retryAfterSeconds", retry));
        }
    }

    private com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView doLogin(
            String username, LoginCommand command, ClientContext client) {
        char[] password = command.password();
        var user = users.findByUsername(username).orElse(null);
        if (user == null) {
            runDummyVerification(password);
            recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                    .c3Anonymous(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, anonymousRef(username),
                            FailureReason.UNKNOWN_USER.name(), null), client);
            // Không ghi auth_attempt cho unknown user: `auth_attempt.user_id` NOT NULL FK (04 §4.2.6);
            // design/01 §A.3 chỉ yêu cầu audit C3 với entity anonymous.
            throw invalidCredentials();
        }
        FailureReason early = evaluateState(user);
        if (early == FailureReason.ACCOUNT_LOCKED) {
            recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                    .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, user.userId(),
                            FailureReason.ACCOUNT_LOCKED.name(), null), client);
            throw locked(user.lockedUntil());
        }
        if (early == FailureReason.ACCOUNT_DISABLED) {
            recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                    .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, user.userId(),
                            FailureReason.ACCOUNT_DISABLED.name(), null), client);
            throw forbidden();
        }
        boolean matches = verifySafely(user, password);
        if (!matches) {
            registerFailure(user, client);
        }
        return completePrimaryAuthentication(user, command, client);
    }

    private FailureReason evaluateState(UserAccountRepository.AppUserSnapshot user) {
        Instant now = clock.instant();
        if ("DISABLED".equals(user.status())) {
            return FailureReason.ACCOUNT_DISABLED;
        }
        if ("LOCKED".equals(user.status()) && user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            return FailureReason.ACCOUNT_LOCKED;
        }
        if ("LOCKED".equals(user.status())) {
            // design/04 §4.1.3: lockout hết hạn → unlock + reset counter trước khi verify lại.
            users.unlockIfLockExpired(user.userId());
            // Đọc lại state sau unlock (race: request khác vừa khóa lại) — fail-closed.
            var refreshed = users.findByUsername(user.username()).orElseThrow();
            if ("DISABLED".equals(refreshed.status())) {
                return FailureReason.ACCOUNT_DISABLED;
            }
            if ("LOCKED".equals(refreshed.status()) && refreshed.lockedUntil() != null
                    && refreshed.lockedUntil().isAfter(clock.instant())) {
                return FailureReason.ACCOUNT_LOCKED;
            }
        }
        return null;
    }

    private void runDummyVerification(char[] password) {
        try {
            hasher.matches(hasher.dummyHash(), password);
        } catch (PasswordHashFormatException neverThrown) {
            LOG.warn("Dummy hash rejected");
        }
    }

    /** T08: stored hash sai định dạng → safe failure (đếm như attempt thất bại, không crash, không lộ). */
    private boolean verifySafely(UserAccountRepository.AppUserSnapshot user, char[] password) {
        try {
            return hasher.matches(user.passwordHash(), password);
        } catch (PasswordHashFormatException malformed) {
            LOG.warn("Unparseable stored password hash for user {}", user.userId());
            return false;
        }
    }

    private void registerFailure(UserAccountRepository.AppUserSnapshot user, ClientContext client) {
        FailureReason reason = FailureReason.WRONG_PASSWORD;
        var outcome = users.recordFailedAttempt(user.userId(), maxFailedAttempts, lockDurationMinutes);
        if (outcome.isEmpty()) {
            // State đổi giữa chừng (kẻ khác vừa khóa/disable). Re-evaluate một lần, fail-closed.
            var refreshed = users.findByUsername(user.username()).orElseThrow();
            FailureReason state = evaluateState(refreshed);
            if (state == FailureReason.ACCOUNT_LOCKED) {
                recordAuthAttempt(refreshed.userId(), "LOCKED", FailureReason.ACCOUNT_LOCKED.name(), client);
                recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                        .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, refreshed.userId(),
                                FailureReason.ACCOUNT_LOCKED.name(), null), client);
                throw locked(refreshed.lockedUntil());
            }
            if (state == FailureReason.ACCOUNT_DISABLED) {
                recordAuthAttempt(refreshed.userId(), "FAILURE", FailureReason.ACCOUNT_DISABLED.name(), client);
                recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                        .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, refreshed.userId(),
                                FailureReason.ACCOUNT_DISABLED.name(), null), client);
                throw forbidden();
            }
            outcome = users.recordFailedAttempt(refreshed.userId(), maxFailedAttempts, lockDurationMinutes);
            user = refreshed;
        }
        if (outcome.isEmpty()) {
            // Hai lần không bump được → state liên tục đổi; re-read lần cuối và map an toàn.
            var latest = users.findByUsername(user.username()).orElseThrow();
            FailureReason state = evaluateState(latest);
            if (state == FailureReason.ACCOUNT_LOCKED) {
                throw locked(latest.lockedUntil());
            }
            if (state == FailureReason.ACCOUNT_DISABLED) {
                throw forbidden();
            }
            throw invalidCredentials();
        }
        if (outcome.get().lockedNow()) {
            recordAuthAttempt(user.userId(), "LOCKED", FailureReason.WRONG_PASSWORD.name(), client);
            recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                    .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, user.userId(),
                            FailureReason.WRONG_PASSWORD.name(), null), client);
            // design/01 §5.3: lockout state commit trước; audit C3 standalone; alert bắt buộc.
            recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                    .c3User(AuthAuditPort.AuthAction.AUTH_ACCOUNT_LOCKED, user.userId(),
                            FailureReason.WRONG_PASSWORD.name(), null), client);
            throw locked(outcome.get().lockedUntil());
        }
        recordAuthAttempt(user.userId(), "FAILURE", reason.name(), client);
        recordStandaloneAudit(AuthAuditPort.AuthAuditEvent
                .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, user.userId(), reason.name(), null), client);
        throw invalidCredentials();
    }

    private com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView completePrimaryAuthentication(
            UserAccountRepository.AppUserSnapshot user, LoginCommand command, ClientContext client) {
        Long deviceId = transactions.execute(status -> {
            // A valid password is pre-authentication, not AUTH_LOGIN_SUCCESS.
            if (!users.confirmPrimaryAuthentication(user.userId())) {
                var current = users.findById(user.userId()).orElseThrow(PasswordLoginService::forbidden);
                if ("LOCKED".equals(current.status())) {
                    throw locked(current.lockedUntil());
                }
                throw forbidden();
            }
            if (command.deviceFingerprint() == null || command.deviceFingerprint().isBlank()) {
                return null;
            }
            String fingerprintHash = hmac.hmacSha256Hex(command.deviceFingerprint());
            var recognized = command.deviceRef() == null ? null
                    : devices.findOwned(command.deviceRef(), user.userId()).orElse(null);
            if (recognized == null || !fingerprintHash.equals(recognized.fingerprintHash())) {
                recognized = devices.registerUntrusted(user.userId(), fingerprintHash,
                        command.deviceName() == null ? "Unknown device" : command.deviceName());
            }
            if (!"ACTIVE".equals(recognized.status())) {
                throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                        com.simulatedbank.banking.common.error.ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
            }
            return recognized.deviceId();
        });
        // DEC-ID-008: even a recognized trusted row MUST NOT bypass OTP.
        return challenges.requestLoginChallenge(user.userId(), deviceId, client.correlationId());
    }

    private void recordAuthAttempt(long userId, String outcome, String reasonCode, ClientContext client) {
        users.recordAuthAttempt(new UserAccountRepository.AuthAttemptRecord(
                userId, outcome, reasonCode, client.correlationId(), client.clientInfoJson(), "LOGIN"));
    }

    private void recordStandaloneAudit(AuthAuditPort.AuthAuditEvent event, ClientContext client) {
        // C3 standalone: lỗi audit không được ảnh hưởng response (adapter tự nuốt).
        audit.recordStandalone(event, client.correlationId());
    }
    private static String anonymousRef(String normalizedUsername) {
        // design/01 §A.3: entity cho unknown user là `anon:<sha256(username)>` — không lưu username raw.
        return "anon:" + com.simulatedbank.banking.common.crypto.HmacService.sha256Hex(normalizedUsername);
    }

    private static RuntimeException invalidCredentials() {
        return new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    private static RuntimeException locked(Instant lockedUntil) {
        if (lockedUntil == null) {
            return new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        return new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_ACCOUNT_LOCKED,
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_ACCOUNT_LOCKED.detail(),
                Map.of("lockedUntil", lockedUntil));
    }

    private static RuntimeException forbidden() {
        return new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_FORBIDDEN);
    }
}
