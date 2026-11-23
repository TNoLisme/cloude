package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.identity.application.ClientContext;
import com.simulatedbank.banking.identity.application.LoginCommand;
import com.simulatedbank.banking.identity.application.LoginResult;
import com.simulatedbank.banking.identity.application.LogoutService;
import com.simulatedbank.banking.identity.application.PasswordLoginService;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.servlet.ServletContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-ID-IMP-02: integration PostgreSQL thật (Testcontainers) — T01, T02, T04, T05, T09–T14,
 * T15, T16, T17 (auth_attempt), T21 (fail-closed), T22 (response fields). Không mock chứng minh
 * invariant (BE-TST-01..10); HTTP path dùng đúng filter chain + SPI thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(IdentityAuthPostgreSqlIntegrationTest.LoginGatewayConfiguration.class)
class IdentityAuthPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // T11: 5 thread đồng thời, mỗi thread cần connection riêng.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        // Bộ test này dùng chung IP loopback với >10 login; quota per-IP/username đã được
        // kiểm chứng riêng ở HttpSecurityComponentsTest — nâng quota để không nhiễu 429.
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private PasswordLoginService loginService;

    @Autowired
    private OtpChallengeService otpService;

    @Autowired
    private LoginGateway gateway;

    @Autowired
    private LogoutService logoutService;

    @Autowired
    private SessionAuthenticator sessionAuthenticator;

    @Autowired
    private Argon2PasswordHasher hasher;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<SessionAuthenticator> authenticators;

    @Autowired
    private org.springframework.security.web.SecurityFilterChain chain;

    @Autowired
    private ServletContext servletContext;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.base = "http://127.0.0.1:" + port + "/api/v1/auth";
    }

    // ------------------------------------------------------------------ helpers

    private long seedUser(String username, String password, String status, int failedAttempts,
            Instant lockedUntil, String roleCode) {
        return tx.execute(ignored -> {
            String hash = hasher.hash(password.toCharArray());
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status, "
                    + "failed_attempt_count, locked_until) VALUES (?, ?, 'CUSTOMER', ?, ?, ?)",
                    username, hash, status, failedAttempts,
                    lockedUntil == null ? null : Timestamp.from(lockedUntil));
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            return userId;
        });
    }

    private void updateState(String username, String status, Instant lockedUntil, Integer failed) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.app_user SET status = ?, locked_until = ?, failed_attempt_count = ? "
                        + "WHERE username = ?",
                status, lockedUntil == null ? null : Timestamp.from(lockedUntil), failed, username));
    }

    private Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    private record HttpResult(int status, JsonNode body, List<String> setCookie) {
    }

    private HttpResult post(String path, String payload, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload));
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isEmpty()
                ? JSON.nullNode() : JSON.readTree(response.body());
        return new HttpResult(response.statusCode(), body, response.headers().allValues("Set-Cookie"));
    }

    private HttpResult login(String username, String password, Map<String, String> headers) throws Exception {
        HttpResult primary = post("/login", JSON.writeValueAsString(Map.of(
                "username", username, "password", password)), headers);
        if (primary.status() >= 400) return primary;
        assertThat(primary.status()).as("password login: %s", primary.body()).isEqualTo(202);
        assertThat(primary.setCookie()).isEmpty();
        assertThat(primary.body().has("session")).isFalse();
        String ref = primary.body().path("challenge").path("challengeId").asText();
        HttpResult verified = post("/otp/verify", JSON.writeValueAsString(Map.of(
                "challengeId", ref, "otp", gateway.take(ref))), headers);
        assertThat(verified.status()).as("OTP verification: %s", verified.body()).isEqualTo(200);
        return verified;
    }

    private static String cookieValue(List<String> setCookie, String name) {
        return setCookie.stream().filter(value -> value.startsWith(name + "=")).findFirst()
                .map(value -> value.split("=")[1].split(";")[0]).orElseThrow();
    }

    private ClientContext client(String correlationId) {
        return new ClientContext(correlationId, "203.0.113.***", "ua",
                "{\"ip\":\"203.0.113.***\",\"ua\":\"ua\"}");
    }

    // ------------------------------------------------------------------ HTTP tests

    // T01 + T22: login đúng → 200 AuthResponse + Set-Cookie; response không chứa password/hash.
    @Test
    void t01_httpLoginSuccessReturnsSessionAndNoSecrets() throws Exception {
        long userId = seedUser("t01user", "Sup3rSecret!", "ACTIVE", 0, null, "CUSTOMER");

        HttpResult result = login("t01user", "Sup3rSecret!", Map.of("X-Correlation-ID", "corr-t01"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("user").path("userId").asText()).isEqualTo(Long.toString(userId));
        assertThat(result.body().path("user").path("roles").toString()).contains("CUSTOMER");
        assertThat(result.body().path("session").path("csrfToken").asText()).isNotBlank();
        assertThat(result.body().path("session").path("deviceTrusted").asBoolean()).isFalse();
        assertThat(result.body().path("correlationId").asText()).isEqualTo("corr-t01");
        assertThat(result.setCookie()).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("DB_SESSION=");
            assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict");
        });
        String raw = JSON.writeValueAsString(result.body());
        assertThat(raw).doesNotContain("Sup3rSecret!").doesNotContain("$argon2").doesNotContain("password_hash");
    }

    // T02 + T04: sai password và unknown user trả cùng HTTP 401 + cùng contract Problem.
    @Test
    void t02_t04_httpUnknownUserAndWrongPasswordAreIndistinguishable() throws Exception {
        seedUser("t02user", "AnotherSecret!", "ACTIVE", 0, null, "CUSTOMER");

        HttpResult wrongPassword = login("t02user", "totally-wrong", Map.of());
        HttpResult unknownUser = login("nobody-here", "totally-wrong", Map.of());

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat(wrongPassword.body().path("code").asText()).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(unknownUser.body().path("code").asText()).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(unknownUser.body().path("detail").asText())
                .isEqualTo(wrongPassword.body().path("detail").asText());
        // Khử trường biến đổi theo request; phần còn lại của Problem phải giống hệt.
        assertThat(stripVolatile(unknownUser.body())).isEqualTo(stripVolatile(wrongPassword.body()));
    }

    private static String stripVolatile(JsonNode problem) {
        var copy = JSON.createObjectNode();
        problem.fieldNames().forEachRemaining(field -> {
            if (!field.equals("correlationId") && !field.equals("time") && !field.equals("timestamp")) {
                copy.set(field, problem.get(field));
            }
        });
        return copy.toString();
    }

    // T05: request sai định dạng → 400 VALIDATION_ERROR theo catalog.
    @Test
    void t05_malformedLoginRequestRejected() throws Exception {
        HttpResult missingPassword = post("/login", "{\"username\":\"t01user\"}", Map.of());
        assertThat(missingPassword.status()).isEqualTo(400);
        assertThat(missingPassword.body().path("code").asText()).isEqualTo("VALIDATION_ERROR");

        HttpResult oversized = post("/login", JSON.writeValueAsString(Map.of(
                "username", "a".repeat(65), "password", "x")), Map.of());
        assertThat(oversized.status()).isEqualTo(400);

        HttpResult badJson = post("/login", "not-json", Map.of());
        assertThat(badJson.status()).isEqualTo(400);
        assertThat(badJson.body().path("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ service-level tests

    // T09: dưới ngưỡng → 401; T10: fail thứ 5 → 423 + status LOCKED + locked_until.
    @Test
    void t09_t10_sequentialFailuresReachThresholdAndLock() throws Exception {
        long userId = seedUser("t09user", "Sequential1!", "ACTIVE", 0, null, "CUSTOMER");
        ClientContext ctx = client("corr-t09");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> loginService.login(
                    new LoginCommand("t09user", "wrong-pass".toCharArray(), null, null, null), ctx))
                    .isInstanceOfSatisfying(BankingBusinessException.class, failure ->
                            assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
            assertThat(jdbc.queryForObject(
                    "SELECT failed_attempt_count FROM banking.app_user WHERE user_id = ?", Integer.class,
                    userId)).isEqualTo(attempt);
        }
        assertThatThrownBy(() -> loginService.login(
                new LoginCommand("t09user", "wrong-pass".toCharArray(), null, null, null), ctx))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
                    assertThat((Instant) failure.extensions().get("lockedUntil")).isAfter(Instant.now());
                });
        Map<String, Object> state = row(
                "SELECT status, failed_attempt_count, locked_until FROM banking.app_user WHERE user_id = ?",
                userId);
        assertThat(state.get("status")).isEqualTo("LOCKED");
        assertThat((Integer) state.get("failed_attempt_count")).isEqualTo(5);
        assertThat(state.get("locked_until")).isNotNull();
        // auth_attempt: 4 FAILURE + 1 LOCKED (T18/T19 evidence cấp DB).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? AND outcome = 'FAILURE'",
                Integer.class, userId)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? AND outcome = 'LOCKED'",
                Integer.class, userId)).isEqualTo(1);
    }

    // T11: 5 failed attempts ĐỒNG THỜI — không lost update, không bypass lockout (CyclicBarrier sync).
    @Test
    void t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate() throws Exception {
        seedUser("t11user", "Concurrency1!", "ACTIVE", 0, null, "CUSTOMER");
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        List<Future<Integer>> outcomes;
        try {
            outcomes = pool.invokeAll(java.util.stream.IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            loginService.login(new LoginCommand("t11user", "wrong-pass".toCharArray(),
                                            null, null, null),
                                    client("corr-t11-" + i));
                            return 200;
                        } catch (BankingBusinessException failure) {
                            return failure.code().httpStatus();
                        } catch (Throwable failure) {
                            unexpected.compareAndSet(null, failure);
                            return -1;
                        }
                    }).toList(), 60, TimeUnit.SECONDS);
            for (Future<Integer> outcome : outcomes) {
                outcome.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected.get()).isNull();

        // Đúng một request nhận 423 (attempt chạm ngưỡng), bốn request nhận 401.
        List<Integer> statuses = outcomes.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception ignored) {
                return -1;
            }
        }).toList();
        assertThat(statuses).containsExactlyInAnyOrder(401, 401, 401, 401, 423);

        Map<String, Object> state = row(
                "SELECT status, failed_attempt_count, locked_until FROM banking.app_user WHERE username = ?",
                "t11user");
        assertThat((Integer) state.get("failed_attempt_count")).isEqualTo(5); // không lost update
        assertThat(state.get("status")).isEqualTo("LOCKED");
        assertThat(state.get("locked_until")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_attempt WHERE user_id = (SELECT user_id FROM "
                        + "banking.app_user WHERE username = 't11user')", Integer.class)).isEqualTo(5);
    }

    @Test
    void excessConcurrentFailuresSaturateLockoutWithoutChallengeOrSession() throws Exception {
        long userId = seedUser("lockout-saturation", "Concurrency2!", "ACTIVE", 4, null, "CUSTOMER");
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> outcomes;
        try {
            outcomes = pool.invokeAll(java.util.stream.IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            loginService.login(new LoginCommand("lockout-saturation",
                                            "wrong-pass".toCharArray(), null, null, null),
                                    client("corr-lockout-saturation-" + i));
                            return 200;
                        } catch (BankingBusinessException failure) {
                            return failure.code().httpStatus();
                        }
                    }).toList(), 90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = outcomes.stream().map(future -> {
            try {
                return future.get(90, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }).toList();
        assertThat(statuses).containsOnly(423);
        assertThat(row("SELECT status, failed_attempt_count FROM banking.app_user WHERE user_id = ?", userId))
                .containsEntry("status", "LOCKED")
                .containsEntry("failed_attempt_count", 5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.session WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    // T12: đúng mật khẩu nhưng đang bị khóa → 423; không tạo session.
    @Test
    void t12_correctPasswordDuringLockoutDenied() throws Exception {
        long userId = seedUser("t12user", "LockMePlease", "LOCKED", 5, Instant.now().plusSeconds(600),
                "CUSTOMER");

        assertThatThrownBy(() -> loginService.login(new LoginCommand("t12user", "LockMePlease".toCharArray(),
                        null, null, null),
                client("corr-t12")))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
                    assertThat(failure.extensions().get("lockedUntil")).isNotNull();
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    // T13 + T14: hết hạn khóa → unlock, login thành công, counter reset = 0.
    @Test
    void t13_t14_loginAfterLockExpiryRecoversAndResetsCounters() throws Exception {
        long userId = seedUser("t13user", "RecoverMe123", "LOCKED", 5, Instant.now().minusSeconds(1),
                "CUSTOMER");

        var challenge = loginService.login(new LoginCommand("t13user", "RecoverMe123".toCharArray(),
                null, null, null), client("corr-t13"));

        assertThat(challenge.challengeRef()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id = ?",
                Integer.class, userId)).isZero();
        assertThat(row("SELECT last_login_at FROM banking.app_user WHERE user_id = ?", userId)
                .get("last_login_at")).isNull();
        LoginResult result = otpService.verifyLoginChallenge(challenge.challengeRef(),
                gateway.take(challenge.challengeRef()).toCharArray(), client("corr-t13"));
        assertThat(result.rawSessionToken()).isNotBlank();
        Map<String, Object> state = row(
                "SELECT status, failed_attempt_count, locked_until, last_login_at FROM banking.app_user "
                        + "WHERE user_id = ?", userId);
        assertThat(state.get("status")).isEqualTo("ACTIVE");
        assertThat((Integer) state.get("failed_attempt_count")).isZero();
        assertThat(state.get("locked_until")).isNull();
        assertThat(state.get("last_login_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_attempt WHERE user_id = ? AND outcome = 'SUCCESS'",
                Integer.class, userId)).isEqualTo(1);
        // SPI thật nhận token vừa cấp (chứng minh wiring identity — design/05 §13.2).
        Optional<SessionAuthenticator.Session> authenticated = sessionAuthenticator
                .authenticate(result.rawSessionToken());
        assertThat(authenticated).isPresent();
        assertThat(authenticated.get().userId()).isEqualTo(Long.toString(userId));
        assertThat(authenticated.get().authorities()).contains("ROLE_CUSTOMER", "ACCOUNT_READ_SELF");
    }

    // T15: logout hợp lệ → 204 + cookie cleared; session LOGGED_OUT; token hết giá trị.
    @Test
    void t15_httpLogoutRevokesSessionAndClearsCookie() throws Exception {
        long userId = seedUser("t15user", "LogoutPass1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult loginResult = login("t15user", "LogoutPass1", Map.of("X-Correlation-ID", "corr-t15"));
        assertThat(loginResult.status()).isEqualTo(200);
        String token = cookieValue(loginResult.setCookie(), "DB_SESSION");
        String csrf = loginResult.body().path("session").path("csrfToken").asText();

        // Cookie-auth mutation không có CSRF header → 403 (SecurityFilterChainConfig).
        HttpResult withoutCsrf = post("/logout", "", Map.of("Cookie", "DB_SESSION=" + token));
        assertThat(withoutCsrf.status()).isEqualTo(403);
        assertThat(withoutCsrf.body().path("code").asText()).isEqualTo("AUTH_FORBIDDEN");

        HttpResult logout = post("/logout", "", Map.of("Cookie", "DB_SESSION=" + token,
                "X-CSRF-Token", csrf));
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie().toString()).contains("Max-Age=0");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE user_id = ?", String.class, userId))
                .isEqualTo("LOGGED_OUT");
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
    }

    // T16: logout lặp → token không còn hợp lệ → 401 AUTH_SESSION_EXPIRED (07 §8.1.6 idempotent,
    // không lộ thông tin); không đổi state.
    @Test
    void t16_repeatedLogoutReturnsApprovedIdempotentBehavior() throws Exception {
        seedUser("t16user", "RepeatPass1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult loginResult = login("t16user", "RepeatPass1", Map.of());
        String token = cookieValue(loginResult.setCookie(), "DB_SESSION");
        String csrf = loginResult.body().path("session").path("csrfToken").asText();

        assertThat(post("/logout", "", Map.of("Cookie", "DB_SESSION=" + token, "X-CSRF-Token", csrf))
                .status()).isEqualTo(204);
        HttpResult repeated = post("/logout", "", Map.of("Cookie", "DB_SESSION=" + token,
                "X-CSRF-Token", csrf));
        assertThat(repeated.status()).isEqualTo(401);
        assertThat(repeated.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
        // Token không thể dùng lại cho endpoint cần auth.
        HttpResult reuse = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(reuse.status()).isEqualTo(401);
    }

    // T22 (HTTP): Problem 401 không chứa username/hash/chi tiết nội bộ.
    @Test
    void t22_problemResponsesExposeNoInternalState() throws Exception {
        seedUser("t22user", "SensitiveX9", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult failure = login("t22user", "nope-nope", Map.of());
        String raw = failure.body().toString();
        assertThat(raw).doesNotContain("t22user").doesNotContain("SensitiveX9").doesNotContain("$argon2");
    }

    // ==================================================================
    // BE-ID-IMP-03: opaque session, CSRF binding, logout, SPI (AC-004)
    // ==================================================================

    private void expireSession(String token, String sqlSet) {
        // `spring.datasource.hikari.auto-commit: false` — ghi trực tiếp qua jdbc sẽ bị rollback
        // khi trả connection về pool; mọi write test phải qua TransactionTemplate.
        tx.executeWithoutResult(ignored -> jdbc.update("UPDATE banking.session SET " + sqlSet
                + " WHERE session_token_hash = ?", HmacService.sha256Hex(token)));
    }

    private Timestamp column(String token, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM banking.session WHERE session_token_hash = ?",
                Timestamp.class, HmacService.sha256Hex(token));
    }

    // ID-03-T01/T18: phiên ACTIVE thật → SPI trả Session với authority snapshot thật; filter
    // cookie-auth cho phép logout (t15 dưới đây là bằng chứng HTTP end-to-end).
    @Test
    void id03_t01_t18_activeSessionAuthenticatesThroughRealSpi() throws Exception {
        long userId = seedUser("id03t01user", "OpaquePass1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t01user", "OpaquePass1", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");

        Optional<SessionAuthenticator.Session> session = sessionAuthenticator.authenticate(token);

        assertThat(session).isPresent();
        assertThat(session.get().userId()).isEqualTo(Long.toString(userId));
        assertThat(session.get().authorities()).contains("ROLE_CUSTOMER");
        assertThat(session.get().csrfTokenHash()).matches("[0-9a-f]{64}");
        // Raw token không nằm trong snapshot (opaque, không rò rỉ).
        assertThat(session.get().toString()).doesNotContain(token);
    }

    // ID-03-T02: thiếu session ở endpoint protected → 401 AUTH_SESSION_EXPIRED (không 500).
    @Test
    void id03_t02_missingSessionRejected() throws Exception {
        HttpResult noCredentials = post("/logout", "", Map.of());
        assertThat(noCredentials.status()).isEqualTo(401);
        assertThat(noCredentials.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
    }

    // ID-03-T03: opaque token không tồn tại / sai định dạng → 401; Problem không chứa token.
    @Test
    void id03_t03_invalidOpaqueSessionRejected() throws Exception {
        HttpResult unknown = post("/logout", "", Map.of("Authorization", "Bearer synthetic-nonexistent-token"));
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");

        HttpResult malformed = post("/logout", "", Map.of("Authorization", "Bearer bad token with space!"));
        assertThat(malformed.status()).isEqualTo(401);
        assertThat(malformed.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");

        assertThat(sessionAuthenticator.authenticate("synthetic-nonexistent-token")).isEmpty();
        String problem = unknown.body().toString();
        assertThat(problem).doesNotContain("synthetic-nonexistent-token");
    }

    // ID-03-T04: session bị revoke ở DB → SPI trả empty (fail-closed, không fallback).
    @Test
    void id03_t04_revokedSessionRejected() throws Exception {
        seedUser("id03t04user", "RevokeMe123", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t04user", "RevokeMe123", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET status = 'LOGGED_OUT', logged_out_at = now() "
                        + "WHERE session_token_hash = ?", HmacService.sha256Hex(token)));

        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        HttpResult reuse = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
    }

    // ID-03-T05: idle hết hạn → SPI empty; mọi nỗ lực "hoạt động" không hồi sinh
    // (UPDATE chỉ chạy khi idle_expires_at > now() — last_activity_at không đổi).
    @Test
    void id03_t05_idleExpiredSessionRejectedWithoutRevival() throws Exception {
        long userId = seedUser("id03t05user", "IdleOut1234", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t05user", "IdleOut1234", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        expireSession(token, "idle_expires_at = now() - interval '1 second', "
                + "last_activity_at = now() - interval '1 second'");
        Timestamp expiredActivity = column(token, "last_activity_at");
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty(); // activity không hồi sinh
        HttpResult reuse = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
        // Cả hai lần xác thực đều không đụng tới dòng hết hạn (last_activity giữ nguyên giá trị đã hết hạn).
        assertThat(column(token, "last_activity_at")).isEqualTo(expiredActivity);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(token))).isEqualTo("ACTIVE"); // unusable dù status chưa purge
    }

    // ID-03-T06 + T08: absolute hết hạn → SPI empty dù idle còn hạn; activity không gia hạn
    // absolute (LEAST(now()+idle, absolute) không bao giờ vượt absolute; UPDATE có điều kiện
    // absolute_expires_at > now() nên phiên hết hạn không được đụng tới).
    @Test
    void id03_t06_t08_absoluteExpiredSessionRejectedWithoutExtension() throws Exception {
        seedUser("id03t06user", "Absolute9", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t06user", "Absolute9", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        expireSession(token, "created_at = now() - interval '9 hours', "
                + "absolute_expires_at = now() - interval '1 second', "
                + "idle_expires_at = now() + interval '15 minutes', "
                + "last_activity_at = now() - interval '1 second'");
        Timestamp absoluteBefore = column(token, "absolute_expires_at");

        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        HttpResult reuse = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(reuse.status()).isEqualTo(401);
        // absolute không bị dịch bởi activity.
        assertThat(column(token, "absolute_expires_at")).isEqualTo(absoluteBefore);
    }

    // ID-03-T07: activity trước idle timeout → gia hạn idle theo chính sách design/04 §4.3:
    // idle_expires_at = LEAST(now() + idleWindow, absolute) — không bao giờ vượt absolute;
    // absolute không đổi.
    @Test
    void id03_t07_activityBeforeIdleTimeoutRefreshesIdleCappedAtAbsolute() throws Exception {
        seedUser("id03t07user", "RefreshP9", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t07user", "RefreshP9", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        expireSession(token, "idle_expires_at = now() + interval '10 seconds', "
                + "absolute_expires_at = now() + interval '30 seconds'");
        Timestamp absoluteCap = column(token, "absolute_expires_at");

        Optional<SessionAuthenticator.Session> session = sessionAuthenticator.authenticate(token);
        assertThat(session).isPresent();

        Timestamp idleAfter = column(token, "idle_expires_at");
        Timestamp absoluteAfter = column(token, "absolute_expires_at");
        assertThat(idleAfter).isAfter(column(token, "last_activity_at"));
        assertThat(idleAfter).isBeforeOrEqualTo(absoluteAfter); // cap tại absolute
        assertThat(absoluteAfter).isEqualTo(absoluteCap);       // không gia hạn absolute
    }

    // ID-03-T10: CSRF proof sai → 403; session KHÔNG bị revoke bởi logout thất bại.
    @Test
    void id03_t10_invalidCsrfProofRejected() throws Exception {
        seedUser("id03t10user", "CsrfWrong12", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t10user", "CsrfWrong12", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");

        HttpResult invalid = post("/logout", "", Map.of(
                "Cookie", "DB_SESSION=" + token,
                "X-CSRF-Token", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertThat(invalid.status()).isEqualTo(403);
        assertThat(invalid.body().path("code").asText()).isEqualTo("AUTH_FORBIDDEN");

        assertThat(sessionAuthenticator.authenticate(token)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(token))).isEqualTo("ACTIVE");
    }

    // ID-03-T11: CSRF proof của session khác → 403 (không thể dùng token phiên A cho cookie B).
    @Test
    void id03_t11_csrfProofFromAnotherSessionRejected() throws Exception {
        seedUser("id03t11user", "CrossSess12", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult first = login("id03t11user", "CrossSess12", Map.of());
        HttpResult second = login("id03t11user", "CrossSess12", Map.of());
        String cookieA = cookieValue(first.setCookie(), "DB_SESSION");
        String csrfA = first.body().path("session").path("csrfToken").asText();
        String cookieB = cookieValue(second.setCookie(), "DB_SESSION");
        assertThat(cookieA).isNotEqualTo(cookieB);

        HttpResult cross = post("/logout", "", Map.of(
                "Cookie", "DB_SESSION=" + cookieB, "X-CSRF-Token", csrfA));
        assertThat(cross.status()).isEqualTo(403);
        assertThat(cross.body().path("code").asText()).isEqualTo("AUTH_FORBIDDEN");
        // Session B vẫn còn và token của A không khớp hash lưu trong B.
        assertThat(sessionAuthenticator.authenticate(cookieB)).isPresent();
    }

    // ID-03-T12/T13: logout hợp lệ + reuse sau logout → 401 (t15/t16 đã có evidence HTTP;
    // test này bổ sung: logout qua bearer không cần CSRF, revoke thật ở DB).
    @Test
    void id03_t12_t13_logoutRevokesAndReuseRejected() throws Exception {
        seedUser("id03t12user", "RevokeTok1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t12user", "RevokeTok1", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");

        HttpResult logout = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie().toString()).contains("Max-Age=0");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(token))).isEqualTo("LOGGED_OUT");
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        HttpResult reuse = post("/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.body().path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
    }

    // ID-03-T15: logout và authenticate ĐỒNG THỜI — validateAndRefresh chỉ UPDATE dòng ACTIVE,
    // revoke điều kiện WHERE status='ACTIVE' → không thể hồi sinh phiên đã logout.
    @Test
    void id03_t15_concurrentLogoutAndAuthenticationNeverReactivatesSession() throws Exception {
        seedUser("id03t15user", "RaceLogout1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("id03t15user", "RaceLogout1", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        long userId = jdbc.queryForObject(
                "SELECT user_id FROM banking.app_user WHERE username = 'id03t15user'", Long.class);

        int threads = 4;
        int iterations = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        java.util.concurrent.atomic.AtomicBoolean logoutReturned = new java.util.concurrent.atomic.AtomicBoolean(
                false);
        java.util.concurrent.atomic.AtomicReference<Throwable> unexpected = new java.util.concurrent.atomic.AtomicReference<>();
        List<java.util.concurrent.Callable<Void>> authWorkers = java.util.stream.IntStream.range(0, threads)
                .<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int n = 0; n < iterations && unexpected.get() == null; n++) {
                        // Invariant kiểm tra tại thời điểm BẮT ĐẦU call: authenticate bắt đầu sau
                        // khi revoke đã commit phải fail-closed. Call bắt đầu trước nhưng trả về
                        // sau flag là xác thực hợp lệ trong cửa sổ đồng thời, không phải hồi sinh.
                        boolean afterLogoutCommitted = logoutReturned.get();
                        Optional<SessionAuthenticator.Session> session = sessionAuthenticator
                                .authenticate(token);
                        if (afterLogoutCommitted && session.isPresent()) {
                            unexpected.compareAndSet(null, new AssertionError(
                                    "Session được xác thực sau khi logout đã trả về true"));
                        }
                    }
                    return null;
                }).toList();
        try {
            var logoutFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                logoutService.logout(token, userId, "corr-id03-t15");
                logoutReturned.set(true);
                return null;
            });
            var authFutures = authWorkers.stream().map(pool::submit).toList();
            logoutFuture.get(60, TimeUnit.SECONDS);
            for (var future : authFutures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected.get()).isNull();
        // Sau khi logout commit: mọi xác thực tiếp theo fail-closed; status không thể về ACTIVE.
        assertThat(logoutReturned).isTrue();
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(token))).isEqualTo("LOGGED_OUT");
    }

    @Test
    void concurrentDuplicateLogoutCommitsExactlyOneRevocation() throws Exception {
        long userId = seedUser("duplicate-logout", "DuplicateLogout1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult result = login("duplicate-logout", "DuplicateLogout1", Map.of());
        String token = cookieValue(result.setCookie(), "DB_SESSION");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> outcomes;
        try {
            outcomes = pool.invokeAll(java.util.stream.IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return logoutService.logout(token, userId, "corr-duplicate-logout-" + i);
                    }).toList(), 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Boolean> revoked = outcomes.stream().map(future -> {
            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }).toList();
        assertThat(revoked).filteredOn(Boolean.TRUE::equals).hasSize(1);
        assertThat(revoked).filteredOn(Boolean.FALSE::equals).hasSize(threads - 1);
        assertThat(sessionAuthenticator.authenticate(token)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(token))).isEqualTo("LOGGED_OUT");
    }

    // ID-03-T17: session fixation — cookie do client gán trước login không bao giờ trở thành
    // phiên hợp lệ; mỗi login tạo token mới (32B SecureRandom) không tái dùng.
    @Test
    void id03_t17_sessionFixationAttemptRejected() throws Exception {
        seedUser("id03t17user", "FixationP1", "ACTIVE", 0, null, "CUSTOMER");
        HttpResult first = login("id03t17user", "FixationP1",
                Map.of("Cookie", "DB_SESSION=AttackerChosenToken123456"));
        assertThat(first.status()).isEqualTo(200);
        String freshToken = cookieValue(first.setCookie(), "DB_SESSION");
        assertThat(freshToken).isNotEqualTo("AttackerChosenToken123456");
        assertThat(sessionAuthenticator.authenticate("AttackerChosenToken123456")).isEmpty();
        assertThat(sessionAuthenticator.authenticate(freshToken)).isPresent();

        HttpResult second = login("id03t17user", "FixationP1", Map.of());
        assertThat(cookieValue(second.setCookie(), "DB_SESSION")).isNotEqualTo(freshToken);
    }

    // ID-03-T19: không SPI/filter trùng — đúng một bean SessionAuthenticator, đúng một
    // SessionAuthenticationFilter/CsrfFilter/CorsFilter trong chain duy nhất, không đăng ký
    // servlet filter trùng.
    @Test
    void id03_t19_noDuplicateSpiOrFilterRegistration() {
        assertThat(authenticators.stream().toList()).hasSize(1);
        assertThat(authenticators.stream().toList().get(0))
                .isInstanceOf(com.simulatedbank.banking.identity.adapter.out.IdentitySessionAuthenticator.class);

        Map<String, Long> filterCounts = chain.getFilters().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        filter -> filter.getClass().getName(), java.util.stream.Collectors.counting()));
        assertThat(filterCounts)
                .containsEntry("com.simulatedbank.banking.common.config.SessionAuthenticationFilter", 1L)
                .containsEntry("org.springframework.security.web.csrf.CsrfFilter", 1L)
                .containsEntry("org.springframework.web.filter.CorsFilter", 1L);

        var chainRegistrations = servletContext.getFilterRegistrations().values().stream()
                .filter(registration -> "springSecurityFilterChain".equals(registration.getName())).toList();
        assertThat(chainRegistrations).hasSize(1);
        assertThat(servletContext.getFilterRegistrations().values().stream()
                .filter(registration -> registration.getClassName()
                        .contains("SessionAuthenticationFilter")).findAny()).isEmpty();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LoginGatewayConfiguration {
        @Bean
        LoginGateway authRegressionLoginGateway() { return new LoginGateway(); }
    }

    /** Test-only provider acceptance; the OTP still traverses public verification. */
    static final class LoginGateway implements OtpGateway {
        private final Map<String, char[]> codes = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return new OtpGateway.Result(Outcome.ACCEPTED, Failure.NONE);
        }

        String take(String ref) {
            char[] otp = codes.remove(ref);
            assertThat(otp).as("captured OTP for %s", ref).isNotNull();
            try {
                return new String(otp);
            } finally {
                java.util.Arrays.fill(otp, '\0');
            }
        }
    }
}
