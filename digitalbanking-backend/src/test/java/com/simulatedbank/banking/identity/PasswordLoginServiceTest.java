package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.DeviceRepository;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.ClientContext;
import com.simulatedbank.banking.identity.application.LoginCommand;
import com.simulatedbank.banking.identity.application.PasswordLoginService;
import com.simulatedbank.banking.identity.application.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit test logic login/lockout/audit (BE-ID-IMP-02): T01–T04, T06–T10, T12–T14, T17–T19, T21, T22.
 * Repo/port mock đúng hợp đồng trong `identity.application`; Argon2id là implementation thật.
 */
class PasswordLoginServiceTest {

    private static final String PASSWORD = "correct-horse";
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    private UserAccountRepository users;
    private DeviceRepository devices;
    private OtpChallengeService challenges;
    private HmacService hmac;
    private AuthAuditPort audit;
    private Argon2PasswordHasher hasher;
    private PasswordLoginService service;
    private com.simulatedbank.banking.common.config.RequestRateLimiter limiter;
    private com.simulatedbank.banking.identity.application.AuthMetrics metrics;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;
    private ClientContext client;

    private static AppProperties appProperties() {
        return appProperties(10);
    }

    /** `loginPerUsernamePerMinute` cấu hình được để test quota username (BE-ID-IMP-13). */
    private static AppProperties appProperties(int loginPerUsernamePerMinute) {
        var security = new AppProperties.Security(
                new AppProperties.Security.Cookie("DB_SESSION", false, "Strict", "/", 28800L),
                List.of("http://localhost:5173"),
                new AppProperties.Security.Session(15, 8),
                new AppProperties.Security.Lockout(5, 10),
                null,
                new AppProperties.Security.RateLimit(10000, loginPerUsernamePerMinute, 10000, 10000, 10000,
                        10000, 10000),
                new AppProperties.Security.Http(65536, 10000),
                null);
        return new AppProperties("Asia/Ho_Chi_Minh", null, security, null, null, null, null);
    }

    private static UserAccountRepository.AppUserSnapshot user(String status, int failed, Instant lockedUntil,
            String hash) {
        return new UserAccountRepository.AppUserSnapshot(101L, "customer01", "CUSTOMER", hash, status,
                failed, lockedUntil);
    }

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        devices = mock(DeviceRepository.class);
        challenges = mock(OtpChallengeService.class);
        hmac = mock(HmacService.class);
        audit = mock(AuthAuditPort.class);
        hasher = new Argon2PasswordHasher();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        limiter = new com.simulatedbank.banking.common.config.RequestRateLimiter(
                Clock.fixed(NOW, ZoneId.of("UTC")), appProperties(), meterRegistry);
        metrics = new com.simulatedbank.banking.identity.application.AuthMetrics(meterRegistry);
        service = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), appProperties(), txManager, hmac, limiter, metrics);
        client = new ClientContext("corr-1", "203.0.113.7", "UA", "{\"ip\":\"203.0.113.***\",\"ua\":\"UA\"}");
        when(users.confirmPrimaryAuthentication(101L)).thenReturn(true);
        when(challenges.requestLoginChallenge(101L, null, "corr-1")).thenReturn(
                new StepUpChallengePort.ChallengeView("login-challenge",
                        StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE, "OTP", "SMS",
                        NOW.plusSeconds(300), 5, 5));
    }

    private LoginCommand command() {
        return new LoginCommand("customer01", PASSWORD.toCharArray(), null, null, null);
    }

    private LoginCommand wrongCommand() {
        return new LoginCommand("customer01", "wrong-pass".toCharArray(), null, null, null);
    }

    // T01: valid primary credentials issue only a challenge, never session credentials.
    @Test
    void t01_validAccountCorrectPasswordRequiresOtpWithoutSessionGrant() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 0, null, stored)));
        StepUpChallengePort.ChallengeView result = service.login(command(), client);

        assertThat(result.challengeRef()).isEqualTo("login-challenge");
        assertThat(result.purpose()).isEqualTo(StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE);
        assertThat(result.method()).isEqualTo("OTP");
        verify(challenges).requestLoginChallenge(101L, null, "corr-1");
        verify(users).confirmPrimaryAuthentication(101L);
        verify(users, never()).resetCountersAndMarkLoginSuccess(anyLong());
        verify(users, never()).recordAuthAttempt(any());
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    // T02: sai mật khẩu → 401 generic (code + detail giống unknown user).
    @Test
    void t02_wrongPasswordThrowsGenericInvalidCredentials() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 0, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(1, false, null)));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
                    assertThat(failure.extensions()).isEmpty();
                });
    }

    // T03: unknown account → dummy Argon2id verification phải được thực thi (full cost).
    @Test
    void t03_unknownAccountRunsDummyArgon2Verification() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        var hasherSpy = org.mockito.Mockito.spy(new Argon2PasswordHasher());

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var spiedService = new PasswordLoginService(users, devices, challenges, audit, hasherSpy,
                Clock.fixed(NOW, ZoneId.of("UTC")), appProperties(), txManager, hmac, limiter, metrics);

        assertThatThrownBy(() -> spiedService.login(
                new LoginCommand("Ghost", PASSWORD.toCharArray(), null, null, null), client))
                .isInstanceOf(BankingBusinessException.class);

        String expectedDummy = hasherSpy.dummyHash();
        verify(hasherSpy).matches(eq(expectedDummy), any());
        verify(challenges, never()).requestLoginChallenge(anyLong(), any(), anyString());
    }

    // T04: unknown user và sai password trả cùng exception contract (không lộ tồn tại tài khoản).
    @Test
    void t04_unknownUserAndWrongPasswordShareIdenticalContract() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        BankingBusinessException unknown;
        try {
            service.login(new LoginCommand("ghost", PASSWORD.toCharArray(), null, null, null), client);
            throw new AssertionError("login should fail");
        } catch (BankingBusinessException failure) {
            unknown = failure;
        }
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 0, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(1, false, null)));
        BankingBusinessException wrongPassword = capture(() -> service.login(wrongCommand(), client));

        assertThat(wrongPassword.code()).isEqualTo(unknown.code());
        assertThat(wrongPassword.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(wrongPassword.extensions()).isEqualTo(unknown.extensions());
    }

    // T09: fail dưới ngưỡng → không khóa, trả 401 generic, counter tăng qua repo nguyên tử.
    @Test
    void t09_failedAttemptBelowThresholdStaysUnlocked() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 3, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(4, false, null)));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOf(BankingBusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
        verify(users).recordFailedAttempt(101L, 5, 10);
        verify(users, never()).unlockIfLockExpired(anyLong());
    }

    // T10: fail thứ 5 (outcome.lockedNow) → 423 + lockedUntil extension + audit AUTH_ACCOUNT_LOCKED.
    @Test
    void t10_fifthFailedAttemptLocksAccount() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(10));
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 4, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(5, true, lockedUntil)));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
                    assertThat(failure.extensions().get("lockedUntil")).isEqualTo(lockedUntil);
                });
        verify(audit, times(2)).recordStandalone(any(), eq("corr-1"));
        verify(users).recordAuthAttempt(any(UserAccountRepository.AuthAttemptRecord.class));
    }

    // T08: stored hash sai định dạng → safe failure 401, không crash.
    @Test
    void t08_invalidStoredHashFailsSafely() {
        when(users.findByUsername("customer01")).thenReturn(
                Optional.of(user("ACTIVE", 0, null, "$2y$10$malformed-not-argon2")));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(1, false, null)));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOf(BankingBusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    // T12: đang bị khóa (locked_until tương lai) → 423 trước cả khi verify password.
    @Test
    void t12_loginDuringLockoutRejectedBeforePasswordVerification() {
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(10));
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("LOCKED", 5, lockedUntil,
                hasher.hash(PASSWORD.toCharArray()))));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
                    assertThat(failure.extensions().get("lockedUntil")).isEqualTo(lockedUntil);
                });
        verify(users, never()).recordFailedAttempt(anyLong(), anyInt(), anyInt());
    }

    // T12b: DISABLED → 403 (không verify, không bump).
    @Test
    void t12b_disabledAccountForbidden() {
        when(users.findByUsername("customer01")).thenReturn(
                Optional.of(user("DISABLED", 0, null, hasher.hash(PASSWORD.toCharArray()))));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOf(BankingBusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    // T13: lockout hết hạn → unlock + login tiếp theo thành công.
    @Test
    void t13_loginAfterLockoutExpiryRecovers() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("LOCKED", 5,
                NOW.minusSeconds(1), stored)));
        when(users.unlockIfLockExpired(101L)).thenReturn(true);
        when(users.confirmPrimaryAuthentication(101L)).thenReturn(true);

        StepUpChallengePort.ChallengeView result = service.login(command(), client);
        assertThat(result.challengeRef()).isEqualTo("login-challenge");
        verify(users, never()).resetCountersAndMarkLoginSuccess(anyLong());
        verify(users).unlockIfLockExpired(101L);
    }

    // T14: primary authentication resets counters but fails closed if state changed.
    @Test
    void t14_successfulLoginResetsCountersFailClosed() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 2, null, stored)));
        when(users.confirmPrimaryAuthentication(101L)).thenReturn(false);

        assertThatThrownBy(() -> service.login(command(), client)).isInstanceOf(RuntimeException.class);
        verify(users, never()).resetCountersAndMarkLoginSuccess(anyLong());
        verify(challenges, never()).requestLoginChallenge(anyLong(), any(), anyString());
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    // T17/T18/T19: no success audit before OTP; failed/locked primary attempts retain C3.
    @Test
    void t17_to_t19_auditEventsEmittedOnlyForFailedPrimaryAuthentication() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 0, null, stored)));
        service.login(command(), client);
        verify(audit, never()).recordInTransaction(any(), anyString());

        var auditCapture = org.mockito.ArgumentCaptor.forClass(AuthAuditPort.AuthAuditEvent.class);
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 4, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(5, true, NOW.plusSeconds(600))));
        assertThatThrownBy(() -> service.login(wrongCommand(), client)).isInstanceOf(BankingBusinessException.class);
        verify(audit, org.mockito.Mockito.times(2)).recordStandalone(auditCapture.capture(), eq("corr-1"));
        List<AuthAuditPort.AuthAuditEvent> events = auditCapture.getAllValues();
        assertThat(events.get(0).action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED);
        assertThat(events.get(1).action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_ACCOUNT_LOCKED);
    }

    // T21: lỗi DB → 503 SERVICE_UNAVAILABLE (fail-closed), không cấp session, không audit success.
    @Test
    void t21_databaseFailurePropagatesFailClosed() {
        when(users.findByUsername(anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        assertThatThrownBy(() -> service.login(command(), client))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(challenges, never()).requestLoginChallenge(anyLong(), any(), anyString());
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    // T22 (log): không password/hash/token trong security log của audit adapter.
    @Test
    void t22_noSensitiveDataInAuditLogs() {
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT");
        auditLogger.addAppender(capture);
        try {
            new com.simulatedbank.banking.identity.adapter.out.SecurityLogAuditAdapter(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                    .recordStandalone(AuthAuditPort.AuthAuditEvent
                            .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, 101L,
                                    "WRONG_PASSWORD", null), "corr-1");
        } finally {
            auditLogger.detachAppender(capture);
        }
        String logged = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logged).contains("AUTH_LOGIN_FAILED").doesNotContain(PASSWORD).doesNotContain("$argon2");
    }

    private static BankingBusinessException capture(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (BankingBusinessException failure) {
            return failure;
        }
    }

    // ------------------------------------------------------------------ BE-ID-IMP-13

    /** T-RL-01: quota username → 429 RATE_LIMITED với Retry-After thật (giây). */
    @Test
    void tRL01_usernameQuotaReturnsRateLimitedWithRetryAfter() {
        when(users.findByUsername("customer01"))
                .thenReturn(Optional.of(user("ACTIVE", 0, null, hasher.hash(PASSWORD.toCharArray()))));
        var quotaProps = appProperties(1);
        var quotaService = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), quotaProps,
                mock(PlatformTransactionManager.class), hmac, limiter, metrics);

        quotaService.login(command(), client);
        var rejected = capture(() -> quotaService.login(command(), client));

        assertThat(rejected.code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(rejected.extensions().get("retryAfterSeconds")).isInstanceOf(Long.class);
        assertThat((Long) rejected.extensions().get("retryAfterSeconds")).isBetween(1L, 86_400L);
        // Lần thứ hai bị chặn TRƯỚC credential verify: chỉ 1 lần lookup.
        verify(users, times(1)).findByUsername("customer01");
    }

    /** T-RL-02: casing khác nhau dùng chung bucket đã normalize (USER01 == user01). */
    @Test
    void tRL02_casingCannotBypassUsernameQuota() {
        when(users.findByUsername("customer01"))
                .thenReturn(Optional.of(user("ACTIVE", 0, null, hasher.hash(PASSWORD.toCharArray()))));
        var quotaProps = appProperties(1);
        var quotaService = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), quotaProps,
                mock(PlatformTransactionManager.class), hmac, limiter, metrics);

        quotaService.login(command(), client);
        var rejected = capture(() -> quotaService.login(
                new LoginCommand("CUSTOMER01", PASSWORD.toCharArray(), null, null, null), client));

        assertThat(rejected.code()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    /** T-RL-03: quota áp TRƯỚC credential verify — unknown user cũng tiêu thụ bucket. */
    @Test
    void tRL03_quotaIsEnforcedBeforeCredentialVerification() {
        var quotaProps = appProperties(1);
        var quotaService = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), quotaProps,
                mock(PlatformTransactionManager.class), hmac, limiter, metrics);

        var unknown = new LoginCommand("ghost", PASSWORD.toCharArray(), null, null, null);
        capture(() -> quotaService.login(unknown, client));
        var rejected = capture(() -> quotaService.login(unknown, client));

        assertThat(rejected.code()).isEqualTo(ErrorCode.RATE_LIMITED);
        // Dummy Argon2id chỉ chạy đúng 1 lần; lần chặn thứ hai không chạm credential verify.
        verify(users, times(1)).findByUsername("ghost");
    }

    /** T-RL-04: bucket đầy (capacity) → 503 fail-closed như common filter. */
    @Test
    void tRL04_limiterCapacityFailureMapsToServiceUnavailable() {
        var capacityProps = new AppProperties("Asia/Ho_Chi_Minh", null,
                new AppProperties.Security(new AppProperties.Security.Cookie("DB_SESSION", false, "Strict", "/", 28800L),
                        List.of("http://localhost:5173"), new AppProperties.Security.Session(15, 8),
                        new AppProperties.Security.Lockout(5, 10), null, null,
                        new AppProperties.Security.Http(65536, 0), null),
                null, null, null, null);
        var tinyLimiter = new com.simulatedbank.banking.common.config.RequestRateLimiter(
                Clock.fixed(NOW, ZoneId.of("UTC")), capacityProps, meterRegistry);
        var service = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), appProperties(),
                mock(PlatformTransactionManager.class), hmac, tinyLimiter, metrics);

        assertThatThrownBy(() -> service.login(command(), client))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    /** T-MET-01: metric attempt/duration cho thành công và thất bại. */
    @Test
    void tMET01_loginAttemptsAreCountedWithDuration() {
        String stored = hasher.hash(PASSWORD.toCharArray());
        when(users.findByUsername("customer01")).thenReturn(Optional.of(user("ACTIVE", 0, null, stored)));
        when(users.recordFailedAttempt(101L, 5, 10)).thenReturn(
                Optional.of(new UserAccountRepository.FailedAttemptOutcome(1, false, null)));

        assertThatThrownBy(() -> service.login(wrongCommand(), client))
                .isInstanceOf(BankingBusinessException.class);
        service.login(command(), client);

        var failureCounter = meterRegistry.counter("auth.login.requests", "method", "PASSWORD",
                "result", "FAILURE", "reason", "AUTH_INVALID_CREDENTIALS");
        var successCounter = meterRegistry.counter("auth.login.requests", "method", "PASSWORD",
                "result", "SUCCESS", "reason", "NONE");
        assertThat(failureCounter.count()).isEqualTo(1.0);
        assertThat(successCounter.count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("auth.login.duration", "method", "PASSWORD").count())
                .isEqualTo(2L);
        assertThat(meterRegistry.counter("rate.limit.rejections", "group", "login-username").count())
                .isEqualTo(0.0);
    }

    /** T-MET-02: username quota bị từ chối được đếm; label không chứa username/userId. */
    @Test
    void tMET02_usernameQuotaRejectionsAreCounted() {
        when(users.findByUsername("customer01"))
                .thenReturn(Optional.of(user("ACTIVE", 0, null, hasher.hash(PASSWORD.toCharArray()))));
        var quotaProps = appProperties(1);
        var quotaService = new PasswordLoginService(users, devices, challenges, audit, hasher,
                Clock.fixed(NOW, ZoneId.of("UTC")), quotaProps,
                mock(PlatformTransactionManager.class), hmac, limiter, metrics);

        quotaService.login(command(), client);
        capture(() -> quotaService.login(command(), client));

        assertThat(meterRegistry.counter("auth.login.rate_limited", "group", "login-username").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("rate.limit.rejections", "group", "login-username").count())
                .isEqualTo(1.0);
        for (var meter : meterRegistry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertThat(tag.getValue()).doesNotContain("customer01", "hunter2", "$argon2");
            }
        }
    }
}
