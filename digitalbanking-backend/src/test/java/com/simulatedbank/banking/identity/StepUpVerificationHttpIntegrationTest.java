package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.in.StepUpVerificationRequest;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.notification.api.OtpGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real HTTP/filter/session/Identity/PostgreSQL evidence for API-AUTH-05. The gateway and
 * consuming controller below are TEST-ONLY fixtures, not production Transfer integration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import({StepUpVerificationHttpIntegrationTest.FixtureConfiguration.class,
        StepUpVerificationHttpIntegrationTest.TestOnlyConsumer.class})
class StepUpVerificationHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERIFY = "/api/v1/auth/step-up/verify";
    private static final String CONSUME = "/api/v1/test-only/step-up/consume";
    private static final String PASSWORD = "HttpStepUpPassword12";
    private static final AtomicInteger USERS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("step_up_http").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "1000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private DataSource source;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private Argon2PasswordHasher hasher;
    @Autowired
    private StepUpChallengePort challenges;
    @Autowired
    private CapturingGateway gateway;
    @Autowired
    private TestOnlyConsumer consumer;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HttpClient http;
    private Actor actor;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(manager);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        gateway.clear();
        consumer.executions.set(0);
        actor = loginNewUser();
    }

    @AfterEach
    void clearSecrets() {
        gateway.clear();
    }

    @Test
    void authenticationAndCookieCsrfAreRequiredBeforeVerification() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        String payload = proof(issued, "OTP", issued.otp());
        assertProblem(post(VERIFY, payload, Map.of()), 401, "AUTH_SESSION_EXPIRED");
        assertProblem(post(VERIFY, payload, Map.of("Cookie", actor.cookie())), 403, "AUTH_FORBIDDEN");
        assertProblem(post(VERIFY, payload, Map.of("Cookie", actor.cookie(), "X-CSRF-Token", "wrong")),
                403, "AUTH_FORBIDDEN");
        assertSentUntouched(issued);
    }

    @Test
    void validOtpVerifiesOnlyOwnedBoundRowAndReturnsExactSafeSchema() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        Issued untouched = issue(ChallengePurpose.TRANSFER_STEP_UP);
        String correlation = UUID.randomUUID().toString();
        Map<String, String> headers = new java.util.HashMap<>(actor.headers());
        headers.put("X-Correlation-ID", correlation);
        Result result = post(VERIFY, proof(issued, "OTP", issued.otp()), headers);
        assertThat(result.status()).isEqualTo(200);
        Set<String> fields = new HashSet<>();
        result.body().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("challengeId", "status", "purpose", "verifiedAt",
                "expiresAt", "correlationId");
        assertThat(result.body().path("challengeId").asText()).isEqualTo(issued.ref());
        assertThat(result.body().path("status").asText()).isEqualTo("VERIFIED");
        assertThat(result.body().path("purpose").asText()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(result.body().path("correlationId").asText()).isEqualTo(correlation);
        assertThat(Instant.parse(result.body().path("verifiedAt").asText())).isBeforeOrEqualTo(Instant.now());
        Map<String, Object> row = row(issued);
        assertThat(row.get("status")).isEqualTo("VERIFIED");
        assertThat(row.get("verified_at")).isNotNull();
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("request_hash")).isEqualTo(issued.hash());
        assertThat(row.get("idempotency_key")).isEqualTo(issued.key());
        assertThat(Instant.parse(result.body().path("expiresAt").asText()))
                .isEqualTo(((Timestamp) row.get("expires_at")).toInstant());
        assertNoSecrets(result, issued);
        assertSentUntouched(untouched);
        assertThat(consumer.executions.get()).isZero();
    }

    @Test
    void sensitiveActionIsAnAllowedAuthenticatedPurpose() throws Exception {
        Issued issued = issue(ChallengePurpose.SENSITIVE_ACTION);
        Result result = verify(issued, issued.otp());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("purpose").asText()).isEqualTo("SENSITIVE_ACTION");
        assertThat(row(issued).get("status")).isEqualTo("VERIFIED");
    }

    @Test
    void wrongOtpCommitsAttemptAndReturns401WithoutEchoingProof() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        Result result = verify(issued, wrongOtp(issued));
        assertProblem(result, 401, "AUTH_OTP_INVALID");
        assertThat(((Number) row(issued).get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row(issued).get("status")).isEqualTo("SENT");
        assertNoSecrets(result, issued);
    }

    @Test
    void foreignChallengeIsNotFoundAndClientActorHeadersCannotOverrideSession() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        Actor other = loginNewUser();
        Map<String, String> headers = new java.util.HashMap<>(other.headers());
        headers.put("X-User-Id", Long.toString(actor.id()));
        assertProblem(post(VERIFY, proof(issued, "OTP", issued.otp()), headers),
                404, "AUTH_CHALLENGE_NOT_FOUND");
        assertProblem(post(VERIFY, JSON.writeValueAsString(Map.of("challengeId", UUID.randomUUID().toString(),
                "method", "OTP", "otp", issued.otp())), actor.headers()), 404, "AUTH_CHALLENGE_NOT_FOUND");
        assertSentUntouched(issued);
    }

    @Test
    void unsupportedMethodNeverUsesOtpOrMutatesChallenge() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        List<Map<String, Object>> proofs = List.of(
                Map.of("challengeId", issued.ref(), "method", "PASSCODE", "passcode", "123456"),
                Map.of("challengeId", issued.ref(), "method", "FIDO", "fido", Map.of(
                        "credentialId", "AQID", "clientDataJSON", "AQID",
                        "authenticatorData", "AQID", "signature", "AQID")));
        for (Map<String, Object> proof : proofs) {
            Result result = post(VERIFY, JSON.writeValueAsString(proof),
                    actor.headers());
            assertProblem(result, 409, "STEP_UP_CHALLENGE_MISMATCH");
            assertNoSecrets(result, issued);
        }
        assertSentUntouched(issued);
        assertThat(new StepUpVerificationRequest(issued.ref(), "OTP", issued.otp().toCharArray()).toString())
                .doesNotContain(issued.otp());
    }

    @Test
    void invalidMethodOrNestedProofIsBadRequestWithoutMutatingChallenge() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        for (String method : List.of("unknown", "FIDO")) {
            Result result = post(VERIFY, JSON.writeValueAsString(Map.of("challengeId", issued.ref(),
                    "method", method, "otp", issued.otp(), "fido", Map.of())), actor.headers());
            assertProblem(result, 400, "VALIDATION_ERROR");
            assertNoSecrets(result, issued);
        }
        assertSentUntouched(issued);
    }

    @Test
    void expiredProofReturns410() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        expire(issued);
        assertProblem(verify(issued, issued.otp()), 410, "AUTH_OTP_EXPIRED");
        assertThat(row(issued).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    void exceedingAttemptsLocksChallengeAndEvenCorrectProofCannotUpgradeIt() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        int max = ((Number) row(issued).get("max_attempts")).intValue();
        for (int attempt = 1; attempt <= max; attempt++) {
            assertProblem(verify(issued, wrongOtp(issued)), attempt == max ? 423 : 401,
                    attempt == max ? "AUTH_CHALLENGE_LOCKED" : "AUTH_OTP_INVALID");
        }
        assertProblem(verify(issued, issued.otp()), 423, "AUTH_CHALLENGE_LOCKED");
        assertThat(row(issued).get("status")).isEqualTo("LOCKED");
        assertThat(((Number) row(issued).get("attempt_count")).intValue()).isEqualTo(max);
    }

    @Test
    void malformedOrIncompleteInputIsRejectedWithoutMutatingChallenge() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        List<Map<String, Object>> invalid = List.of(
                Map.of("method", "OTP", "otp", issued.otp()),
                Map.of("challengeId", "not-a-uuid", "method", "OTP", "otp", issued.otp()),
                Map.of("challengeId", issued.ref(), "otp", issued.otp()),
                Map.of("challengeId", issued.ref(), "method", "", "otp", issued.otp()),
                Map.of("challengeId", issued.ref(), "method", "OTP"),
                Map.of("challengeId", issued.ref(), "method", "OTP", "otp", "12345"),
                Map.of("challengeId", issued.ref(), "method", "OTP", "otp", "123456789"),
                Map.of("challengeId", issued.ref(), "method", "OTP", "otp", "12a456"),
                Map.of("challengeId", issued.ref(), "method", "OTP", "otp", " 123456"));
        for (Map<String, Object> body : invalid) {
            assertProblem(post(VERIFY, JSON.writeValueAsString(body), actor.headers()), 400, "VALIDATION_ERROR");
        }
        for (Object otp : List.of(List.of("1", "2", "3", "4", "5", "6"), 123456)) {
            assertProblem(post(VERIFY, JSON.writeValueAsString(Map.of("challengeId", issued.ref(),
                    "method", "OTP", "otp", otp)), actor.headers()), 400, "MALFORMED_REQUEST");
        }
        assertProblem(post(VERIFY, "{", actor.headers()), 400, "MALFORMED_REQUEST");
        assertProblem(post(VERIFY, JSON.writeValueAsString(Map.of("challengeId", issued.ref(), "method", "OTP",
                "otp", issued.otp(), "userId", actor.id())), actor.headers()), 400, "MALFORMED_REQUEST");
        assertSentUntouched(issued);
    }

    @Test
    void loginChallengeCannotBeUpgradedByAuthenticatedStepUpEndpoint() throws Exception {
        Issued issued = issue(ChallengePurpose.LOGIN_NEW_DEVICE);
        assertProblem(verify(issued, issued.otp()), 409, "STEP_UP_CHALLENGE_MISMATCH");
        assertSentUntouched(issued);
    }

    @Test
    void testOnlyConsumerReturnsActual428EnvelopeForSentChallengeWithoutExecuting() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        Result result = consume(issued, issued.hash(), issued.key());
        assertProblem(result, 428, "STEP_UP_REQUIRED");
        JsonNode challenge = result.body().path("challenge");
        assertThat(challenge.path("challengeId").asText()).isEqualTo(issued.ref());
        assertThat(challenge.path("purpose").asText()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(challenge.path("methods")).isEqualTo(JSON.valueToTree(List.of("OTP")));
        assertThat(challenge.path("expiresAt").asText()).isNotBlank();
        assertThat(challenge.path("maxAttempts").asInt()).isPositive();
        assertThat(challenge.path("remainingAttempts").asInt()).isEqualTo(challenge.path("maxAttempts").asInt());
        assertNoSecrets(result, issued);
        assertThat(consumer.executions.get()).isZero();
        assertSentUntouched(issued);
    }

    @Test
    void testOnlyConsumerClassifiesWrongBindingAndExpiryInsteadOf428() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        assertProblem(consume(issued, HmacService.sha256Hex("different"), issued.key()),
                409, "STEP_UP_CHALLENGE_MISMATCH");
        assertProblem(consume(issued, issued.hash(), "different-key"), 409, "STEP_UP_CHALLENGE_MISMATCH");
        expire(issued);
        assertProblem(consume(issued, issued.hash(), issued.key()), 410, "AUTH_CHALLENGE_EXPIRED");
        assertThat(consumer.executions.get()).isZero();
        assertThat(row(issued).get("consumed_at")).isNull();
    }

    @Test
    void testOnlyConsumerExecutesOnceAndConsumedProofCannotBeVerifiedOrConsumedAgain() throws Exception {
        Issued issued = issue(ChallengePurpose.TRANSFER_STEP_UP);
        assertThat(verify(issued, issued.otp()).status()).isEqualTo(200);
        assertThat(consume(issued, issued.hash(), issued.key()).status()).isEqualTo(204);
        assertThat(consumer.executions.get()).isEqualTo(1);
        assertThat(row(issued).get("status")).isEqualTo("VERIFIED");
        assertThat(row(issued).get("consumed_at")).isNotNull();
        assertProblem(verify(issued, issued.otp()), 409, "INVALID_STATE_TRANSITION");
        assertProblem(consume(issued, issued.hash(), issued.key()), 409, "INVALID_STATE_TRANSITION");
        assertThat(consumer.executions.get()).isEqualTo(1);
    }

    private Actor loginNewUser() throws Exception {
        String username = "step-http-" + USERS.incrementAndGet();
        long id = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, hasher.hash(PASSWORD.toCharArray()));
            Long userId = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", userId);
            return userId;
        });
        Result primary = post("/api/v1/auth/login", JSON.writeValueAsString(Map.of("username", username,
                "password", PASSWORD)), Map.of());
        assertThat(primary.status()).isEqualTo(202);
        assertThat(primary.response().headers().allValues("Set-Cookie")).isEmpty();
        String ref = primary.body().path("challenge").path("challengeId").asText();
        char[] otp = gateway.codes.remove(ref);
        assertThat(otp).isNotNull();
        Result result;
        try {
            result = post("/api/v1/auth/otp/verify", JSON.writeValueAsString(Map.of(
                    "challengeId", ref, "otp", new String(otp))), Map.of());
        } finally {
            Arrays.fill(otp, '\0');
        }
        assertThat(result.status()).isEqualTo(200);
        String cookie = result.response().headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("DB_SESSION=")).findFirst().orElseThrow().split(";", 2)[0];
        return new Actor(id, cookie, result.body().path("session").path("csrfToken").asText());
    }

    private Issued issue(ChallengePurpose purpose) {
        String key = UUID.randomUUID().toString();
        String hash = HmacService.sha256Hex("test-payload-" + key);
        boolean login = purpose == ChallengePurpose.LOGIN_NEW_DEVICE;
        var view = challenges.requestChallenge(new StepUpChallengePort.ChallengeRequest(purpose, actor.id(),
                login ? null : hash, login ? null : key, UUID.randomUUID().toString()));
        char[] captured = gateway.codes.get(view.challengeRef());
        assertThat(captured).isNotNull();
        return new Issued(view.challengeRef(), new String(captured), hash, key);
    }

    private Result verify(Issued issued, String otp) throws Exception {
        return post(VERIFY, proof(issued, "OTP", otp), actor.headers());
    }

    private Result consume(Issued issued, String hash, String key) throws Exception {
        return post(CONSUME, JSON.writeValueAsString(Map.of("challengeId", issued.ref(), "requestHash", hash,
                "bindingKey", key)), actor.headers());
    }

    private String proof(Issued issued, String method, String otp) throws Exception {
        return JSON.writeValueAsString(Map.of("challengeId", issued.ref(), "method", method, "otp", otp));
    }

    private String wrongOtp(Issued issued) {
        return "000000".equals(issued.otp()) ? "999999" : "000000";
    }

    private void expire(Issued issued) {
        tx.executeWithoutResult(ignored -> jdbc.update("UPDATE banking.auth_challenge "
                + "SET created_at = now() - interval '10 minutes', expires_at = now() - interval '1 second' "
                + "WHERE challenge_ref = ?::uuid", issued.ref()));
    }

    private Map<String, Object> row(Issued issued) {
        return jdbc.queryForMap("SELECT * FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", issued.ref());
    }

    private void assertSentUntouched(Issued issued) {
        Map<String, Object> row = row(issued);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("verified_at")).isNull();
        assertThat(row.get("consumed_at")).isNull();
    }

    private void assertProblem(Result result, int status, String code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.response().headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(result.body().path("status").asInt()).isEqualTo(status);
        assertThat(result.body().path("code").asText()).isEqualTo(code);
        for (String name : List.of("type", "title", "detail", "instance", "correlationId", "timestamp")) {
            assertThat(result.body().path(name).asText()).as(name).isNotBlank();
        }
        assertThat(result.response().headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private void assertNoSecrets(Result result, Issued issued) {
        assertThat(result.response().body()).doesNotContain(issued.otp(), issued.hash(), issued.key(), "otp_hash");
        assertThat(result.response().headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private Result post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body().isEmpty() ? JSON.nullNode()
                : JSON.readTree(response.body()), response);
    }

    private record Actor(long id, String cookie, String csrf) {
        Map<String, String> headers() {
            return Map.of("Cookie", cookie, "X-CSRF-Token", csrf);
        }
    }

    private record Issued(String ref, String otp, String hash, String key) {
        @Override
        public String toString() { return "Issued[redacted]"; }
    }

    private record Result(int status, JsonNode body, HttpResponse<String> response) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        CapturingGateway testOnlyGateway() { return new CapturingGateway(); }
    }

    /** TEST-ONLY provider acceptance + transient OTP capture; no production fallback gateway. */
    static final class CapturingGateway implements OtpGateway {
        final Map<String, char[]> codes = new ConcurrentHashMap<>();

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return new OtpGateway.Result(Outcome.ACCEPTED, Failure.NONE);
        }

        void clear() {
            codes.values().forEach(code -> Arrays.fill(code, '\0'));
            codes.clear();
        }
    }

    /**
     * TEST-ONLY consuming-port harness. No Transfer, idempotency, balance, ledger or policy
     * implementation is simulated. The counter records only a successful caller-T1 return.
     */
    @RestController
    @TestComponent
    static class TestOnlyConsumer {
        private final StepUpChallengePort challenges;
        private final TransactionTemplate transaction;
        final AtomicInteger executions = new AtomicInteger();

        TestOnlyConsumer(StepUpChallengePort challenges, PlatformTransactionManager manager) {
            this.challenges = challenges;
            this.transaction = new TransactionTemplate(manager);
        }

        @PostMapping(CONSUME)
        ResponseEntity<Void> consume(@RequestBody ConsumptionRequest request, HttpServletRequest servlet) {
            Object value = servlet.getAttribute(
                    "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
            if (!(value instanceof SessionAuthenticator.Session actor)) {
                throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
            }
            transaction.executeWithoutResult(ignored -> challenges.consumeVerified(
                    new StepUpChallengePort.ConsumeCommand(request.challengeId(), Long.parseLong(actor.userId()),
                            ChallengePurpose.TRANSFER_STEP_UP, request.requestHash(), request.bindingKey())));
            executions.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
    }

    record ConsumptionRequest(String challengeId, String requestHash, String bindingKey) {
    }
}
