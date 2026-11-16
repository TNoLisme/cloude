package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ID-IMP-13 — quota username qua HTTP thật (07 §2.8, design/04 §8.3, DEC-05):
 * normalize nhất quán, 429 + Retry-After thật, không lộ account-existence, không secret trong
 * structured log; metric auth.login.* / rate.limit.rejections qua registry thật của app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(LoginUsernameRateLimitHttpIntegrationTest.FixtureConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginUsernameRateLimitHttpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH = "/api/v1/auth";
    private static final String PASSWORD = "UsernameQuotaPassword12";
    private static final String SECRET_LOGGING_PASSWORD = "SecretLoggingPass99!";
    private static final AtomicInteger USERS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("login_quota_http").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        // Username quota là object under test (3/phút); mọi bucket khác được nới để chỉ test quota username.
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "3");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("app.security.cookie.secure", () -> "false");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Argon2PasswordHasher hasher;
    @Autowired
    private CapturingGateway gateway;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        gateway.clear();
    }

    @AfterEach
    void clearSecrets() {
        gateway.clear();
    }

    @Test
    void exceedingUsernameQuotaReturns429WithRealRetryAfterWithoutAccountEnumeration() throws Exception {
        Actor user = seedUser();
        for (int attempt = 0; attempt < 3; attempt++) {
            post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());
        }
        Result rejected = post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());

        assertThat(rejected.status()).isEqualTo(429);
        assertThat(rejected.body().path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(rejected.body().path("retryable").asBoolean()).isTrue();
        long retryAfter = Long.parseLong(rejected.response().headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(rejected.body().path("retryAfterSeconds").asLong()).isEqualTo(retryAfter);
        // Không tiết lộ user tồn tại/không tồn tại: 429 giống hệt cho unknown user (test sau).
        assertThat(rejected.response().body()).doesNotContain(user.username());
    }

    @Test
    void unknownUsernameGetsTheSameQuotaRejectionShape() throws Exception {
        String ghost = "ghost-quota-" + UUID.randomUUID();
        for (int attempt = 0; attempt < 3; attempt++) {
            post(AUTH + "/login", login(ghost, "whatever"), Map.of());
        }
        Result rejected = post(AUTH + "/login", login(ghost, "whatever"), Map.of());

        assertThat(rejected.status()).isEqualTo(429);
        assertThat(rejected.body().path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(rejected.response().headers().firstValue("Retry-After")).isPresent();
        // Same problem shape as the known-user rejection: no account existence signal.
        assertThat(rejected.body().has("challenge")).isFalse();
        assertThat(rejected.body().has("user")).isFalse();
    }

    @Test
    void casingAndFormattingCannotBypassTheUsernameQuota() throws Exception {
        Actor user = seedUser();
        assertThat(post(AUTH + "/login", login(user.username().toUpperCase(java.util.Locale.ROOT),
                "wrong-password"), Map.of()).status()).isEqualTo(401);
        for (int attempt = 0; attempt < 2; attempt++) {
            post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());
        }
        Result rejected = post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());

        assertThat(rejected.status()).isEqualTo(429);
        assertThat(rejected.body().path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void distinctUsernamesHaveIndependentBuckets() throws Exception {
        Actor first = seedUser();
        Actor second = seedUser();
        for (int attempt = 0; attempt < 3; attempt++) {
            post(AUTH + "/login", login(first.username(), "wrong-password"), Map.of());
        }
        assertThat(post(AUTH + "/login", login(first.username(), "wrong-password"), Map.of()).status())
                .isEqualTo(429);
        // Bucket username khác vẫn còn token → đi tiếp vào luồng MFA, không bị 429.
        Result fresh = post(AUTH + "/login", login(second.username(), PASSWORD), Map.of());
        assertThat(fresh.status()).isEqualTo(202);
        assertThat(fresh.body().path("code").asText()).isEqualTo("AUTH_CHALLENGE_REQUIRED");
    }

    @Test
    void authOutcomeAndRejectionMetricsAreRecordedOnTheSharedRegistry() throws Exception {
        Actor user = seedUser();
        double successBefore = loginRequests("SUCCESS", "NONE");
        double failureBefore = loginRequests("FAILURE", "AUTH_INVALID_CREDENTIALS");
        double rejectionBefore = rejectionCount();

        Result success = post(AUTH + "/login", login(user.username(), PASSWORD), Map.of());
        Result wrong = post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());
        for (int attempt = 0; attempt < 3; attempt++) {
            post(AUTH + "/login", login(user.username(), "wrong-password"), Map.of());
        }

        assertThat(success.status()).isEqualTo(202);
        assertThat(wrong.status()).isEqualTo(401);
        // quota=3: success tiêu token 1, 2 lần wrong tiêu token 2–3, 2 lần wrong còn lại → 429.
        assertThat(loginRequests("SUCCESS", "NONE")).isEqualTo(successBefore + 1);
        assertThat(loginRequests("FAILURE", "AUTH_INVALID_CREDENTIALS")).isEqualTo(failureBefore + 2);
        assertThat(meterRegistry.counter("auth.login.requests", "method", "PASSWORD",
                "result", "FAILURE", "reason", "RATE_LIMITED").count()).isGreaterThanOrEqualTo(2.0);
        assertThat(rejectionCount()).isEqualTo(rejectionBefore + 2);
        assertThat(meterRegistry.timer("auth.login.duration", "method", "PASSWORD").count())
                .isGreaterThanOrEqualTo(5L);
    }

    @Test
    void structuredLogsNeverContainCredentialsOtpOrSessionTokensDuringQuotaFlow(
            CapturedOutput output) throws Exception {
        Actor user = seedUser();
        for (int attempt = 0; attempt < 4; attempt++) {
            post(AUTH + "/login", login(user.username(), SECRET_LOGGING_PASSWORD), Map.of());
        }
        for (char[] otp : gateway.codes.values()) {
            assertThat(output.getAll()).doesNotContain(new String(otp));
        }
        assertThat(output.getAll()).doesNotContain(SECRET_LOGGING_PASSWORD);
        assertThat(output.getAll()).doesNotContain(user.username());
        assertThat(output.getAll()).doesNotContain("Authorization", "Bearer ");
        // Password hash không bao giờ xuất hiện.
        assertThat(output.getAll()).doesNotContain("$argon2");
    }

    private double loginRequests(String result, String reason) {
        return meterRegistry.counter("auth.login.requests", "method", "PASSWORD",
                "result", result, "reason", reason).count();
    }

    private double rejectionCount() {
        return meterRegistry.counter("rate.limit.rejections", "group", "login-username").count();
    }

    private Actor seedUser() {
        String username = "quota-http-" + USERS.incrementAndGet();
        tx.execute(status -> jdbc.update(
                "INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                        + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, hasher.hash(PASSWORD.toCharArray())));
        return new Actor(username);
    }

    private Map<String, Object> login(String username, String password) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        return payload;
    }

    private Result post(String path, Map<String, ?> payload, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),
                response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body()), response);
    }

    private record Actor(String username) {
    }

    private record Result(int status, JsonNode body, HttpResponse<String> response) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        CapturingGateway loginQuotaTestGateway() { return new CapturingGateway(); }
    }

    /** TEST ONLY: transient OTP capture to prove the code never reaches logs, cleared after each test. */
    static final class CapturingGateway implements OtpGateway {
        final Map<String, char[]> codes = new ConcurrentHashMap<>();

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
        }

        void clear() {
            codes.values().forEach(code -> Arrays.fill(code, '\0'));
            codes.clear();
        }
    }
}
