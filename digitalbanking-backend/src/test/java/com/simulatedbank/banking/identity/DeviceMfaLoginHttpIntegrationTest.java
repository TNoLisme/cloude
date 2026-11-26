package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.AuthAuditPort.AuthAction;
import com.simulatedbank.banking.identity.application.PasswordLoginService;
import com.simulatedbank.banking.identity.application.LoginCommand;
import com.simulatedbank.banking.identity.application.ClientContext;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IMP-08: actual HTTP/security-chain/PostgreSQL evidence for MFA on EVERY password login.
 * Only notification delivery and an explicitly armed audit failure are test-controlled.
 * This does not implement or claim persistent device trust, secrets, expiry or re-registration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(DeviceMfaLoginHttpIntegrationTest.FixtureConfiguration.class)
class DeviceMfaLoginHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH = "/api/v1/auth";
    private static final String PASSWORD = "DeviceMfaHttpPassword12";
    private static final String USER_AGENT = "Copied-MFA-Test-UA";
    private static final AtomicInteger USERS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("device_mfa_http").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("app.security.cookie.secure", () -> "true");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Argon2PasswordHasher hasher;
    @Autowired
    private CapturingGateway gateway;
    @MockitoSpyBean
    private AuthAuditPort audit;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PasswordLoginService primaryLogin;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HttpClient http;
    private Actor actor;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        tx = new TransactionTemplate(transactionManager);
        gateway.clear();
        actor = seedUser();
    }

    @AfterEach
    void clearSecrets() {
        gateway.clear();
    }

    @Test
    void passwordOnlyAlwaysRequiresMfaAndChallengeCannotAuthenticateProtectedApi() throws Exception {
        Issued issued = begin(actor, Map.of());
        assertChallengeResponse(issued.response());
        assertThat(row(issued).get("device_id")).isNull();
        assertNoSession(actor);
        assertProblem(get(AUTH + "/session", Map.of("Authorization", "Bearer " + issued.ref())),
                401, "AUTH_SESSION_EXPIRED");
        assertProblem(get(AUTH + "/session", Map.of()), 401, "AUTH_SESSION_EXPIRED");
        assertSent(issued);
        assertNoSession(actor);
    }

    @Test
    void copiedTrustedIdFingerprintAndUserAgentNeverBypassMfaOrChangeForeignDevice() throws Exception {
        String fingerprint = "copied-fingerprint-" + UUID.randomUUID();
        begin(actor, metadata(UUID.randomUUID(), fingerprint));
        Map<String, Object> device = device(actor);
        UUID ref = (UUID) device.get("device_ref");
        update("UPDATE banking.device SET trusted = true, trusted_at = now() WHERE device_id = ?",
                device.get("device_id"));
        Issued trusted = begin(actor, metadata(ref, fingerprint));
        assertChallengeResponse(trusted.response());
        assertThat(row(trusted).get("device_id")).isEqualTo(device.get("device_id"));
        assertNoSession(actor);

        Actor foreign = seedUser();
        Issued copied = begin(foreign, metadata(ref, fingerprint));
        assertChallengeResponse(copied.response());
        assertNoSession(foreign);
        Map<String, Object> foreignDevice = device(foreign);
        assertThat(foreignDevice.get("device_ref")).isNotEqualTo(ref);
        assertThat(row(copied).get("device_id")).isEqualTo(foreignDevice.get("device_id"));
        Map<String, Object> unchanged = jdbc.queryForMap("SELECT * FROM banking.device WHERE device_id = ?",
                device.get("device_id"));
        assertThat(unchanged.get("user_id")).isEqualTo(actor.id());
        assertThat(unchanged.get("trusted")).isEqualTo(true);
        assertThat(unchanged.get("last_seen_at")).isNull();
        assertThat(foreignDevice.get("trusted")).isEqualTo(false);
    }

    @Test
    void unknownAndForeignReferencesWithoutFingerprintNeverAssociateAnotherUsersDevice() throws Exception {
        begin(actor, metadata(UUID.randomUUID(), "owner-" + UUID.randomUUID()));
        Map<String, Object> owned = device(actor);
        Actor other = seedUser();
        for (UUID ref : List.of(UUID.randomUUID(), (UUID) owned.get("device_ref"))) {
            Issued issued = begin(other, Map.of("deviceRef", ref.toString(), "deviceName", "Copied name"));
            assertThat(row(issued).get("device_id")).isNull();
            assertNoSession(other);
        }
        assertThat(count("SELECT count(*) FROM banking.device WHERE user_id = ?", other.id())).isZero();
        assertThat(device(actor)).isEqualTo(owned);
    }

    @Test
    void otpReturnsExactAuthResponseAndConsumesProofInSamePersistedSessionDeviceBoundary() throws Exception {
        String fingerprint = "new-device-" + UUID.randomUUID();
        Issued issued = begin(actor, metadata(UUID.randomUUID(), fingerprint));
        Map<String, Object> before = device(actor);
        assertThat(before.get("device_fingerprint_hash").toString()).matches("[0-9a-f]{64}")
                .isNotEqualTo(HmacService.sha256Hex(fingerprint)).doesNotContain(fingerprint);
        assertThat(before.get("trusted")).isEqualTo(false);
        assertThat(before.get("trusted_at")).isNull();
        String correlation = UUID.randomUUID().toString();
        Result result = verify(issued.ref(), issued.otp(), Map.of("X-Correlation-ID", correlation));
        assertAuthResponse(result, actor, correlation);
        Map<String, Object> challenge = row(issued);
        Map<String, Object> session = jdbc.queryForMap("SELECT * FROM banking.session WHERE user_id = ?", actor.id());
        assertThat(challenge.get("status")).isEqualTo("VERIFIED");
        assertThat(challenge.get("verified_at")).isNotNull();
        assertThat(challenge.get("consumed_at")).isNotNull();
        assertThat(((Number) challenge.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(challenge.get("device_id")).isEqualTo(before.get("device_id"));
        assertThat(session.get("device_id")).isEqualTo(before.get("device_id"));
        assertThat(session.get("status")).isEqualTo("ACTIVE");
        String token = sessionToken(result);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(session.get("session_token_hash")).isEqualTo(HmacService.sha256Hex(token));
        assertThat(session.get("csrf_token_hash"))
                .isEqualTo(HmacService.sha256Hex(result.body().path("session").path("csrfToken").asText()));
        // PostgreSQL TIMESTAMPTZ rounds Java nanoseconds to microsecond storage precision.
        assertThat(Duration.between(((Timestamp) session.get("idle_expires_at")).toInstant(),
                Instant.parse(result.body().path("session").path("idleExpiresAt").asText())).abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1000));
        assertThat(Duration.between(((Timestamp) session.get("absolute_expires_at")).toInstant(),
                Instant.parse(result.body().path("session").path("absoluteExpiresAt").asText())).abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1000));
        assertThat(lastLogin(actor)).isNotNull();
        Map<String, Object> after = device(actor);
        assertThat(after.get("last_seen_at")).isNotNull();
        assertThat(after.get("trusted")).isEqualTo(false);
        assertThat(after.get("trusted_at")).isNull();
        assertThat(count("SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? "
                + "AND attempt_type = 'OTP' AND outcome = 'SUCCESS'", actor.id())).isEqualTo(1);
        assertThat(get(AUTH + "/session", Map.of("Cookie", "DB_SESSION=" + token)).status()).isEqualTo(200);
        assertProblem(verify(issued), 409, "INVALID_STATE_TRANSITION");
        assertThat(sessionCount(actor)).isEqualTo(1);
    }

    @Test
    void successfulMfaDoesNotGrantOrExtendPersistedTrustEvenForPreviouslyTrustedMetadata() throws Exception {
        String fingerprint = "pretrusted-" + UUID.randomUUID();
        Issued first = begin(actor, metadata(UUID.randomUUID(), fingerprint));
        update("UPDATE banking.device SET trusted = true, trusted_at = now() - interval '1 day' WHERE user_id = ?",
                actor.id());
        Map<String, Object> before = device(actor);
        Issued issued = begin(actor, metadata((UUID) before.get("device_ref"), fingerprint));
        Result result = verify(issued);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("session").path("deviceTrusted").asBoolean()).isFalse();
        assertThat(device(actor).get("trusted")).isEqualTo(true);
        assertThat(device(actor).get("trusted_at")).isEqualTo(before.get("trusted_at"));
        assertSent(first);
    }

    @Test
    void wrongOtpCommitsOnlyFailedAttemptAndCanThenSucceedWithOriginalProof() throws Exception {
        Issued issued = begin(actor, Map.of());
        assertProblem(verify(issued.ref(), wrong(issued), Map.of()), 401, "AUTH_OTP_INVALID");
        assertThat(row(issued).get("status")).isEqualTo("SENT");
        assertThat(((Number) row(issued).get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row(issued).get("consumed_at")).isNull();
        assertNoSession(actor);
        assertThat(count("SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? "
                + "AND attempt_type = 'OTP' AND outcome = 'FAILURE'", actor.id())).isEqualTo(1);
        assertThat(verify(issued).status()).isEqualTo(200);
        assertThat(sessionCount(actor)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM banking.session WHERE user_id = ?", actor.id()).get("device_id")).isNull();
    }

    @Test
    void expiredProofIsDeniedWithoutSession() throws Exception {
        Issued issued = begin(actor, Map.of());
        update("UPDATE banking.auth_challenge SET created_at = now() - interval '10 minutes', "
                + "expires_at = now() - interval '1 second' WHERE challenge_ref = ?::uuid", issued.ref());
        assertProblem(verify(issued), 410, "AUTH_OTP_EXPIRED");
        assertThat(row(issued).get("status")).isEqualTo("EXPIRED");
        assertThat(row(issued).get("consumed_at")).isNull();
        assertNoSession(actor);
    }

    @Test
    void exhaustingOtpAttemptsLocksProofAndCorrectOtpCannotIssueSessionAfterwards() throws Exception {
        Issued issued = begin(actor, Map.of());
        int max = ((Number) row(issued).get("max_attempts")).intValue();
        for (int attempt = 1; attempt <= max; attempt++) {
            assertProblem(verify(issued.ref(), wrong(issued), Map.of()), attempt == max ? 423 : 401,
                    attempt == max ? "AUTH_CHALLENGE_LOCKED" : "AUTH_OTP_INVALID");
        }
        assertProblem(verify(issued), 423, "AUTH_CHALLENGE_LOCKED");
        assertThat(row(issued).get("status")).isEqualTo("LOCKED");
        assertThat(((Number) row(issued).get("attempt_count")).intValue()).isEqualTo(max);
        assertThat(row(issued).get("consumed_at")).isNull();
        assertNoSession(actor);
    }

    @Test
    void purposeAndMethodMismatchUnknownAndMalformedProofNeverIssueSession() throws Exception {
        Issued purpose = begin(actor, Map.of());
        update("UPDATE banking.auth_challenge SET purpose = 'SENSITIVE_ACTION' WHERE challenge_ref = ?::uuid",
                purpose.ref());
        assertProblem(verify(purpose), 409, "INVALID_STATE_TRANSITION");
        assertSent(purpose);
        Issued method = begin(actor, metadata(UUID.randomUUID(), "method-" + UUID.randomUUID()));
        update("UPDATE banking.auth_challenge SET method = 'PASSCODE' WHERE challenge_ref = ?::uuid", method.ref());
        assertProblem(verify(method), 409, "STEP_UP_CHALLENGE_MISMATCH");
        assertSent(method);
        assertProblem(verify(UUID.randomUUID().toString(), "123456", Map.of()), 404, "AUTH_CHALLENGE_NOT_FOUND");
        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.of("challengeId", "not-a-uuid", "otp", "123456"),
                Map.of("challengeId", purpose.ref(), "otp", "12x456"),
                Map.of("challengeId", purpose.ref(), "otp", "12345"),
                Map.of("challengeId", purpose.ref()), Map.of("otp", "123456"),
                Map.of("challengeId", purpose.ref(), "otp", "123456", "userId", actor.id()))) {
            Result malformed = post(AUTH + "/otp/verify", payload, Map.of());
            assertThat(malformed.status()).isEqualTo(400);
            assertNoCookie(malformed);
        }
        Result brokenJson = send("POST", AUTH + "/otp/verify", "{", Map.of());
        assertThat(brokenJson.status()).isEqualTo(400);
        assertNoCookie(brokenJson);
        assertNoSession(actor);
    }

    @Test
    void proofsCannotBeSubstitutedBetweenDistinctAttemptsOrUsersAndHeadersCannotChooseActor() throws Exception {
        Issued first = begin(actor, metadata(UUID.randomUUID(), "attempt-one-" + UUID.randomUUID()));
        Issued second = distinctAttempt(actor, first, metadata(UUID.randomUUID(), "attempt-two-" + UUID.randomUUID()));
        Actor other = seedUser();
        Issued foreign = distinctAttempt(other, first, Map.of());
        assertThat(first.ref()).isNotEqualTo(second.ref()).isNotEqualTo(foreign.ref());
        assertProblem(verify(second.ref(), first.otp(), Map.of()), 401, "AUTH_OTP_INVALID");
        assertProblem(verify(foreign.ref(), first.otp(), Map.of("X-User-Id", Long.toString(actor.id()))),
                401, "AUTH_OTP_INVALID");
        assertSent(first);
        assertNoSession(actor);
        assertNoSession(other);
        Result accepted = verify(first.ref(), first.otp(), Map.of("X-User-Id", Long.toString(other.id())));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().path("user").path("userId").asText()).isEqualTo(Long.toString(actor.id()));
        assertThat(sessionCount(actor)).isEqualTo(1);
        assertThat(sessionCount(other)).isZero();
        assertThat(jdbc.queryForObject("SELECT device_id FROM banking.session WHERE user_id = ?", Long.class, actor.id()))
                .isEqualTo(((Number) row(first).get("device_id")).longValue());
        assertThat(row(second).get("consumed_at")).isNull();
        assertThat(row(foreign).get("consumed_at")).isNull();
    }

    @RepeatedTest(3)
    void concurrentActualHttpVerificationCommitsExactlyOneSessionAndLoserIs409() throws Exception {
        Issued issued = begin(actor, metadata(UUID.randomUUID(), "race-" + UUID.randomUUID()));
        List<Result> results = simultaneously(() -> verify(issued), () -> verify(issued));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        Result loser = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
        assertProblem(loser, 409, "INVALID_STATE_TRANSITION");
        assertThat(sessionCount(actor)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? "
                + "AND attempt_type = 'OTP' AND outcome = 'SUCCESS'", actor.id())).isEqualTo(1);
        assertThat(row(issued).get("status")).isEqualTo("VERIFIED");
        assertThat(row(issued).get("consumed_at")).isNotNull();
        assertThat(((Number) row(issued).get("attempt_count")).intValue()).isEqualTo(1);
    }

    @Test
    void accountDisabledAfterPasswordCannotCompleteMfa() throws Exception {
        Issued issued = begin(actor, Map.of());
        update("UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = ?", actor.id());
        assertProblem(verify(issued), 403, "AUTH_FORBIDDEN");
        assertSent(issued);
        assertNoSession(actor);
    }

    @Test
    void accountTemporarilyOrPermanentlyLockedAfterPasswordCannotCompleteMfa() throws Exception {
        Issued issued = begin(actor, Map.of());
        update("UPDATE banking.app_user SET locked_until = now() + interval '10 minutes' WHERE user_id = ?",
                actor.id());
        assertProblem(verify(issued), 423, "AUTH_ACCOUNT_LOCKED");
        assertSent(issued);
        assertNoSession(actor);
        update("UPDATE banking.app_user SET status = 'LOCKED', locked_until = null WHERE user_id = ?", actor.id());
        assertProblem(verify(issued), 423, "AUTH_ACCOUNT_LOCKED");
        assertSent(issued);
        assertNoSession(actor);
    }

    @Test
    void deviceRevokedBetweenPasswordAndMfaCannotCompleteOrBeReactivatedByMetadata() throws Exception {
        String fingerprint = "revoke-before-otp-" + UUID.randomUUID();
        Issued issued = begin(actor, metadata(UUID.randomUUID(), fingerprint));
        Map<String, Object> before = device(actor);
        update("UPDATE banking.device SET status = 'REVOKED' WHERE user_id = ?", actor.id());
        assertProblem(verify(issued), 403, "AUTH_DEVICE_NOT_TRUSTED");
        assertSent(issued);
        assertNoSession(actor);
        assertProblem(login(actor, metadata((UUID) before.get("device_ref"), fingerprint)),
                403, "AUTH_DEVICE_NOT_TRUSTED");
        assertProblem(login(actor, metadata(UUID.randomUUID(), fingerprint)), 403, "AUTH_DEVICE_NOT_TRUSTED");
        assertThat(device(actor).get("status")).isEqualTo("REVOKED");
        assertThat(device(actor).get("trusted")).isEqualTo(false);
        assertThat(device(actor).get("last_seen_at")).isNull();
        assertThat(count("SELECT count(*) FROM banking.device WHERE user_id = ?", actor.id())).isEqualTo(1);
        assertNoSession(actor);
    }

    @Test
    void sessionAssociatedWithDeviceIsRejectedOnLaterRequestsAfterDatabaseRevocation() throws Exception {
        Issued issued = begin(actor, metadata(UUID.randomUUID(), "revoke-session-" + UUID.randomUUID()));
        Result loggedIn = verify(issued);
        assertThat(loggedIn.status()).isEqualTo(200);
        String token = sessionToken(loggedIn);
        assertThat(get(AUTH + "/session", Map.of("Authorization", "Bearer " + token)).status()).isEqualTo(200);
        update("UPDATE banking.device SET status = 'REVOKED' WHERE user_id = ?", actor.id());
        assertProblem(get(AUTH + "/session", Map.of("Authorization", "Bearer " + token)),
                401, "AUTH_SESSION_EXPIRED");
        assertProblem(get(AUTH + "/session", Map.of("Cookie", "DB_SESSION=" + token)),
                401, "AUTH_SESSION_EXPIRED");
        assertThat(count("SELECT count(*) FROM banking.session WHERE user_id = ? AND device_id = ?",
                actor.id(), row(issued).get("device_id"))).isEqualTo(1);
    }

    @Test
    void deviceStorageFailureFailsClosedWithoutSessionOrChallengeAndCanRetry() throws Exception {
        String constraint = "test_imp08_device_" + actor.id();
        execute("ALTER TABLE banking.device ADD CONSTRAINT " + constraint
                + " CHECK (user_id <> " + actor.id() + ") NOT VALID");
        try {
            Result failed = login(actor, metadata(UUID.randomUUID(), "device-storage-failure"));
            assertServerFailure(failed);
            assertNoSession(actor);
            assertThat(count("SELECT count(*) FROM banking.device WHERE user_id = ?", actor.id())).isZero();
            assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", actor.id())).isZero();
        } finally {
            execute("ALTER TABLE banking.device DROP CONSTRAINT " + constraint);
        }
        assertThat(verify(begin(actor, metadata(UUID.randomUUID(), "device-storage-failure"))).status()).isEqualTo(200);
    }

    @Test
    void gatewayFailureCannotIssueSessionOrAuthorizeCapturedUndeliveredProof() throws Exception {
        gateway.accept = false;
        assertProblem(login(actor, Map.of()), 503, "OTP_DISPATCH_FAILED");
        assertNoSession(actor);
        assertThat(gateway.codes).hasSize(1);
        String ref = gateway.codes.keySet().iterator().next();
        String otp = new String(gateway.codes.get(ref));
        assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                String.class, ref)).isNotEqualTo("SENT");
        assertProblem(verify(ref, otp, Map.of()), 503, "OTP_DISPATCH_FAILED");
        assertNoSession(actor);
        gateway.accept = true;
        assertThat(verify(begin(actor, Map.of())).status()).isEqualTo(200);
    }

    @Test
    void insertSessionFailureRollsVerificationConsumptionLoginAndDeviceSeenBackThenSameProofCanRetry() throws Exception {
        Issued issued = begin(actor, metadata(UUID.randomUUID(), "insert-session-failure-" + UUID.randomUUID()));
        String constraint = "test_imp08_session_" + actor.id();
        execute("ALTER TABLE banking.session ADD CONSTRAINT " + constraint
                + " CHECK (user_id <> " + actor.id() + ") NOT VALID");
        try {
            assertServerFailure(verify(issued));
            assertCompletionRolledBack(issued);
        } finally {
            execute("ALTER TABLE banking.session DROP CONSTRAINT " + constraint);
        }
        assertThat(verify(issued).status()).isEqualTo(200);
        assertThat(sessionCount(actor)).isEqualTo(1);
    }

    @Test
    void eachC2AuditFailureRollsBackAnActuallyInsertedSessionAndAllowsSameProofRetry() throws Exception {
        for (AuthAction target : List.of(AuthAction.AUTH_STEP_UP_VERIFIED, AuthAction.AUTH_LOGIN_SUCCESS)) {
            Actor current = actor;
            Issued issued = begin(current, metadata(UUID.randomUUID(), "audit-failure-" + UUID.randomUUID()));
            doAnswer(invocation -> {
                AuthAuditPort.AuthAuditEvent event = invocation.getArgument(0);
                if (event.action() == target && Long.valueOf(current.id()).equals(event.actorUserId())) {
                    // The real repositories have already written in the request transaction, not a mock result.
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(sessionCount(current)).isEqualTo(1);
                    assertThat(row(issued).get("status")).isEqualTo("VERIFIED");
                    assertThat(row(issued).get("consumed_at")).isNotNull();
                    assertThat(device(current).get("last_seen_at")).isNotNull();
                    throw new IllegalStateException("test-only audit provider failure");
                }
                return invocation.callRealMethod();
            }).when(audit).recordInTransaction(any(), anyString());
            assertServerFailure(verify(issued));
            assertCompletionRolledBack(issued);
            doAnswer(invocation -> invocation.callRealMethod()).when(audit).recordInTransaction(any(), anyString());
            assertThat(verify(issued).status()).isEqualTo(200);
            assertThat(sessionCount(current)).isEqualTo(1);
            actor = seedUser();
        }
    }

    @RepeatedTest(3)
    void simultaneousUnknownDeviceRegistrationCreatesOneUntrustedRowAndNoPasswordSession() throws Exception {
        UUID unknown = UUID.randomUUID();
        String fingerprint = "parallel-device-" + UUID.randomUUID();
        Map<String, Object> metadata = metadata(unknown, fingerprint);
        List<Result> responses = simultaneously(() -> login(actor, metadata), () -> login(actor, metadata));
        for (Result response : responses) {
            assertChallengeResponse(response);
            String ref = response.body().path("challenge").path("challengeId").asText();
            assertThat(jdbc.queryForObject("SELECT device_id FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                    Long.class, ref)).isEqualTo(((Number) device(actor).get("device_id")).longValue());
        }
        assertThat(responses.get(0).body().path("challenge").path("challengeId").asText())
                .isNotEqualTo(responses.get(1).body().path("challenge").path("challengeId").asText());
        assertThat(count("SELECT count(*) FROM banking.device WHERE user_id = ?", actor.id())).isEqualTo(1);
        Map<String, Object> device = device(actor);
        assertThat(device.get("device_ref")).isNotEqualTo(unknown);
        assertThat(device.get("trusted")).isEqualTo(false);
        assertThat(device.get("trusted_at")).isNull();
        assertThat(device.get("last_seen_at")).isNull();
        assertThat(device.get("status")).isEqualTo("ACTIVE");
        assertNoSession(actor);
    }

    @Test
    void primaryMfaIssuanceCommitsDeviceBeforeOwnChallengeInsideAmbientTransaction() throws Exception {
        var view = tx.execute(status -> {
            var challenge = primaryLogin.login(new LoginCommand(actor.username(), PASSWORD.toCharArray(),
                    UUID.randomUUID(), "Ambient transaction device", "ambient-" + UUID.randomUUID()),
                    new ClientContext(UUID.randomUUID().toString(), null, null, "{}"));
            status.setRollbackOnly();
            return challenge;
        });
        assertThat(view).isNotNull();
        assertThat(count("SELECT count(*) FROM banking.device WHERE user_id = ?", actor.id())).isEqualTo(1);
        assertNoSession(actor);
        char[] otp = gateway.codes.get(view.challengeRef());
        assertThat(otp).isNotNull();
        assertThat(verify(view.challengeRef(), new String(otp), Map.of()).status()).isEqualTo(200);
        assertThat(sessionCount(actor)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryOrRevocationWhileVerificationWaitsForDeviceLockCannotIssueSession(boolean expire) throws Exception {
        Issued issued = begin(actor, metadata(UUID.randomUUID(), "locked-device-" + UUID.randomUUID()));
        long deviceId = ((Number) row(issued).get("device_id")).longValue();
        if (expire) {
            update("UPDATE banking.auth_challenge SET expires_at = clock_timestamp() + interval '5 seconds' "
                    + "WHERE challenge_ref = ?::uuid", issued.ref());
        }
        var pool = Executors.newSingleThreadExecutor();
        List<java.util.concurrent.Future<Result>> pending = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT device_id FROM banking.device WHERE device_id = ? FOR UPDATE",
                        Long.class, deviceId);
                int blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                pending.add(pool.submit(() -> verify(issued)));
                await().pollInSameThread().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5))
                        .until(() -> {
                            jdbc.execute("SELECT pg_stat_clear_snapshot()");
                            return count("SELECT count(*) FROM pg_stat_activity "
                                    + "WHERE ? = ANY(pg_blocking_pids(pid))", blocker) > 0;
                        });
                if (expire) {
                    await().pollInSameThread().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(8))
                            .until(() -> jdbc.queryForObject("SELECT clock_timestamp() >= expires_at "
                                    + "FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                                    Boolean.class, issued.ref()));
                } else {
                    jdbc.update("UPDATE banking.device SET status = 'REVOKED' WHERE device_id = ?", deviceId);
                }
            });
            Result result = pending.get(0).get(20, TimeUnit.SECONDS);
            assertProblem(result, expire ? 410 : 403,
                    expire ? "AUTH_OTP_EXPIRED" : "AUTH_DEVICE_NOT_TRUSTED");
            assertSent(issued);
            assertNoSession(actor);
            assertThat(device(actor).get("last_seen_at")).isNull();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Actor seedUser() {
        String username = "mfa-http-" + USERS.incrementAndGet();
        update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, hasher.hash(PASSWORD.toCharArray()));
        long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
        update("INSERT INTO banking.user_role (user_id, role_id) "
                + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", id);
        return new Actor(id, username);
    }

    private Map<String, Object> metadata(UUID ref, String fingerprint) {
        return Map.of("deviceRef", ref.toString(), "deviceName", "MFA HTTP device", "deviceFingerprint", fingerprint);
    }

    private Result login(Actor user, Map<String, Object> metadata) throws Exception {
        Map<String, Object> payload = new HashMap<>(metadata);
        payload.put("username", user.username());
        payload.put("password", PASSWORD);
        return post(AUTH + "/login", payload, Map.of("User-Agent", USER_AGENT));
    }

    private Issued begin(Actor user, Map<String, Object> metadata) throws Exception {
        Result result = login(user, metadata);
        assertChallengeResponse(result);
        String ref = result.body().path("challenge").path("challengeId").asText();
        char[] otp = gateway.codes.get(ref);
        assertThat(otp).isNotNull();
        return new Issued(ref, new String(otp), user, result);
    }

    private Issued distinctAttempt(Actor user, Issued original, Map<String, Object> metadata) throws Exception {
        // Real random OTPs may collide; bounded issuance avoids a probabilistically flaky substitution test.
        for (int attempt = 0; attempt < 5; attempt++) {
            Issued issued = begin(user, metadata);
            if (!issued.otp().equals(original.otp())) {
                return issued;
            }
        }
        throw new AssertionError("Five independently generated OTPs unexpectedly collided");
    }

    private Result verify(Issued issued) throws Exception {
        return verify(issued.ref(), issued.otp(), Map.of());
    }

    private Result verify(String ref, String otp, Map<String, String> headers) throws Exception {
        return post(AUTH + "/otp/verify", Map.of("challengeId", ref, "otp", otp), headers);
    }

    private String wrong(Issued issued) {
        return "000000".equals(issued.otp()) ? "999999" : "000000";
    }

    private Result post(String path, Map<String, ?> payload, Map<String, String> headers) throws Exception {
        return send("POST", path, JSON.writeValueAsString(payload), headers);
    }

    private Result get(String path, Map<String, String> headers) throws Exception {
        return send("GET", path, "", headers);
    }

    private Result send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (method.equals("GET")) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body().isEmpty() ? JSON.nullNode()
                : JSON.readTree(response.body()), response);
    }

    private List<Result> simultaneously(Callable<Result> first, Callable<Result> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Result>> futures = new ArrayList<>();
            for (Callable<Result> operation : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return operation.call();
                }));
            }
            List<Result> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int update(String sql, Object... parameters) {
        return tx.execute(status -> jdbc.update(sql, parameters));
    }

    private void execute(String sql) {
        tx.executeWithoutResult(status -> jdbc.execute(sql));
    }

    private Map<String, Object> row(Issued issued) {
        return jdbc.queryForMap("SELECT * FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", issued.ref());
    }

    private Map<String, Object> device(Actor user) {
        return jdbc.queryForMap("SELECT * FROM banking.device WHERE user_id = ?", user.id());
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private long sessionCount(Actor user) {
        return count("SELECT count(*) FROM banking.session WHERE user_id = ?", user.id());
    }

    private Timestamp lastLogin(Actor user) {
        return jdbc.queryForObject("SELECT last_login_at FROM banking.app_user WHERE user_id = ?", Timestamp.class, user.id());
    }

    private void assertNoSession(Actor user) {
        assertThat(sessionCount(user)).isZero();
        assertThat(lastLogin(user)).isNull();
        assertThat(count("SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? "
                + "AND outcome = 'SUCCESS'", user.id())).isZero();
    }

    private void assertSent(Issued issued) {
        Map<String, Object> row = row(issued);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("verified_at")).isNull();
        assertThat(row.get("consumed_at")).isNull();
    }

    private void assertCompletionRolledBack(Issued issued) {
        assertSent(issued);
        assertNoSession(issued.actor());
        assertThat(device(issued.actor()).get("last_seen_at")).isNull();
        assertThat(device(issued.actor()).get("trusted")).isEqualTo(false);
        assertThat(device(issued.actor()).get("trusted_at")).isNull();
    }

    private void assertChallengeResponse(Result result) {
        assertThat(result.status()).isEqualTo(202);
        assertFields(result.body(), "code", "message", "challenge", "correlationId");
        assertThat(result.body().path("code").asText()).isEqualTo("AUTH_CHALLENGE_REQUIRED");
        assertThat(result.body().path("message").asText()).isNotBlank();
        assertThat(result.body().path("correlationId").asText()).isNotBlank();
        JsonNode challenge = result.body().path("challenge");
        assertThat(UUID.fromString(challenge.path("challengeId").asText())).isNotNull();
        assertThat(challenge.path("purpose").asText()).isEqualTo("LOGIN_NEW_DEVICE");
        assertThat(challenge.path("methods")).hasSize(1);
        assertThat(challenge.path("methods").get(0).asText()).isEqualTo("OTP");
        assertThat(Instant.parse(challenge.path("expiresAt").asText())).isAfter(Instant.now());
        assertThat(challenge.path("maxAttempts").asInt()).isPositive();
        assertThat(challenge.path("remainingAttempts").asInt()).isEqualTo(challenge.path("maxAttempts").asInt());
        assertNoCookie(result);
        assertSafe(result);
    }

    private void assertAuthResponse(Result result, Actor user, String correlation) {
        assertThat(result.status()).isEqualTo(200);
        assertFields(result.body(), "user", "session", "correlationId");
        assertFields(result.body().path("user"), "userId", "userType", "displayName", "roles", "privileges", "customerId");
        assertFields(result.body().path("session"), "idleExpiresAt", "absoluteExpiresAt", "csrfToken", "deviceTrusted");
        assertThat(result.body().path("user").path("userId").asText()).isEqualTo(Long.toString(user.id()));
        assertThat(result.body().path("user").path("userType").asText()).isEqualTo("CUSTOMER");
        assertThat(result.body().path("user").path("roles").get(0).asText()).isEqualTo("CUSTOMER");
        assertThat(result.body().path("user").path("privileges").isArray()).isTrue();
        assertThat(result.body().path("user").path("privileges").isEmpty()).isTrue();
        assertThat(result.body().path("user").path("customerId").isNull()).isTrue();
        assertThat(result.body().path("correlationId").asText()).isEqualTo(correlation);
        assertThat(result.body().path("session").path("deviceTrusted").asBoolean()).isFalse();
        assertThat(result.body().path("session").path("csrfToken").asText()).isNotBlank();
        assertThat(result.response().headers().allValues("Set-Cookie")).hasSize(1);
        String cookie = result.response().headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("DB_SESSION=").contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=");
        assertThat(result.response().body()).doesNotContain(sessionToken(result));
        assertSafe(result);
    }

    private void assertFields(JsonNode object, String... expected) {
        Set<String> fields = new HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(expected);
    }

    private void assertProblem(Result result, int status, String code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body().path("status").asInt()).isEqualTo(status);
        assertThat(result.body().path("code").asText()).isEqualTo(code);
        assertThat(result.response().headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertNoCookie(result);
        assertSafe(result);
    }

    private void assertServerFailure(Result result) {
        assertThat(result.status()).isBetween(500, 599);
        assertThat(result.body().path("status").asInt()).isEqualTo(result.status());
        assertNoCookie(result);
        assertSafe(result);
    }

    private void assertNoCookie(Result result) {
        assertThat(result.response().headers().allValues("Set-Cookie")).isEmpty();
    }

    private void assertSafe(Result result) {
        assertThat(result.response().headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(result.response().body()).doesNotContain(PASSWORD, "password_hash", "otp_hash", "session_token_hash");
        gateway.codes.values().forEach(code -> assertThat(result.response().body()).doesNotContain(new String(code)));
    }

    private String sessionToken(Result result) {
        return result.response().headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("DB_SESSION="))
                .map(cookie -> cookie.substring("DB_SESSION=".length()).split(";", 2)[0]).findFirst().orElseThrow();
    }

    private record Actor(long id, String username) {
    }

    private record Issued(String ref, String otp, Actor actor, Result response) {
        @Override
        public String toString() { return "Issued[redacted]"; }
    }

    private record Result(int status, JsonNode body, HttpResponse<String> response) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        CapturingGateway deviceMfaTestGateway() { return new CapturingGateway(); }
    }

    /** TEST ONLY: provider outcome control and transient secret capture, cleared after each test. */
    static final class CapturingGateway implements OtpGateway {
        final Map<String, char[]> codes = new ConcurrentHashMap<>();
        volatile boolean accept = true;

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return accept ? new OtpGateway.Result(Outcome.ACCEPTED, Failure.NONE)
                    : new OtpGateway.Result(Outcome.FAILED, Failure.REJECTED);
        }

        void clear() {
            codes.values().forEach(code -> Arrays.fill(code, '\0'));
            codes.clear();
            accept = true;
        }
    }
}
