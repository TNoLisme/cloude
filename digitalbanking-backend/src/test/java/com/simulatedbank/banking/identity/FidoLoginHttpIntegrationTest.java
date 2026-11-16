package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real HTTP, Yubico signatures and PostgreSQL; only external delivery/audit failure are fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(FidoLoginHttpIntegrationTest.Fixtures.class)
class FidoLoginHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/auth/fido/verify";
    private static final String RP = "bank.example";
    private static final String ORIGIN = "https://bank.example";
    private static final String PASSWORD = "FidoHttpPassword12!";
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fido_http").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        r.add("app.identity.fido.enabled", () -> "true");
        r.add("app.identity.fido.rp-id", () -> RP);
        r.add("app.identity.fido.allowed-origins", () -> ORIGIN);
        r.add("app.identity.fido.challenge-ttl", () -> "PT30S");
        r.add("app.security.rate-limit.login-per-ip-per-minute", () -> "10000");
        r.add("app.security.rate-limit.login-per-username-per-minute", () -> "10000");
        r.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        r.add("app.security.cookie.secure", () -> "true");
    }
    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired Argon2PasswordHasher hasher;
    @Autowired Gateway gateway;
    @MockitoSpyBean AuthAuditPort audit;
    @MockitoSpyBean FidoAssertionVerifier verifier;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    org.springframework.transaction.support.TransactionTemplate tx;
    JdbcTemplate jdbc;
    HttpClient http;
    FidoTestAuthenticator authenticator;
    long userId;
    long deviceId;
    String username;
    String deviceRef;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        authenticator = FidoTestAuthenticator.create();
        username = "fido-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            userId = jdbc.queryForObject("INSERT INTO banking.app_user(username,password_hash,user_type) VALUES (?,?,'CUSTOMER') RETURNING user_id",
                    Long.class, username, hasher.hash(PASSWORD.toCharArray()));
            deviceRef = UUID.randomUUID().toString();
            deviceId = jdbc.queryForObject("""
                    INSERT INTO banking.device(device_ref,user_id,device_name,device_fingerprint_hash,trusted,
                      fido_credential_id,fido_public_key_cose,fido_user_handle,fido_sign_count)
                    VALUES (CAST(? AS uuid),?,'FIDO fixture',?,true,?,?,?,0) RETURNING device_id
                    """, Long.class, deviceRef, userId, "a".repeat(64), authenticator.credentialId(),
                    authenticator.publicKeyCose(), authenticator.userHandle());
        });
    }
    @AfterEach void clear() { gateway.codes.values().forEach(c -> Arrays.fill(c, '\0')); gateway.codes.clear(); }

    @Test void validAssertionConsumesChallengeAdvancesCounterAndAuthenticatesRealSession() throws Exception {
        JsonNode challenge = begin();
        assertThat(challenge.path("fidoRequestOptions").path("userVerification").asText()).isEqualTo("required");
        var assertion = sign(challenge, 1);
        var result = finish(challenge, assertion);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("session").path("deviceTrusted").asBoolean()).isTrue();
        String cookie = result.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/");
        String token = cookie.substring("DB_SESSION=".length()).split(";", 2)[0];
        var protectedResponse = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/session"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedResponse.statusCode()).isEqualTo(200);
        assertThat(countSessions()).isEqualTo(1);
        assertThat(counter()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM banking.auth_challenge WHERE challenge_ref=CAST(? AS uuid)",
                Boolean.class, challenge.path("challengeId").asText())).isTrue();
        assertProblem(finish(challenge, assertion), 409, "INVALID_STATE_TRANSITION");
        assertThat(countSessions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_type FROM banking.auth_attempt WHERE user_id=? AND outcome='SUCCESS'",
                String.class, userId)).isEqualTo("FIDO");
    }

    @Test void zeroCounterAuthenticatorCanAuthenticateAcrossDistinctCeremonies() throws Exception {
        var a = begin(); assertThat(finish(a, sign(a, 0)).statusCode()).isEqualTo(200);
        var b = begin(); assertThat(finish(b, sign(b, 0)).statusCode()).isEqualTo(200);
        assertThat(counter()).isZero(); assertThat(countSessions()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"rp", "origin", "type", "challenge", "up", "uv", "signature", "credential", "handle"})
    void invalidAssertionsNeverConsumeOrIssueTokens(String defect) throws Exception {
        var challenge = begin();
        String nonce = challenge.path("fidoRequestOptions").path("challenge").asText();
        var a = authenticator.sign(defect.equals("challenge") ? "c3Vic3RpdHV0ZWQ" : nonce,
                defect.equals("rp") ? "evil.example" : RP,
                defect.equals("origin") ? "https://evil.example" : ORIGIN, 1,
                defect.equals("up") ? 4 : defect.equals("uv") ? 1 : 5,
                defect.equals("type") ? "webauthn.create" : "webauthn.get", false);
        if (defect.equals("signature")) {
            a = new FidoAssertionVerifier.Assertion(a.credentialId(), a.clientDataJSON(), a.authenticatorData(), "AQID", a.userHandle());
        } else if (defect.equals("credential") || defect.equals("handle")) {
            a = new FidoAssertionVerifier.Assertion(defect.equals("credential") ? "dW5rbm93bg" : a.credentialId(),
                    a.clientDataJSON(), a.authenticatorData(), a.signature(), defect.equals("handle") ? "b3RoZXI" : a.userHandle());
        }
        assertProblem(finish(challenge, a), 401, "AUTH_INVALID_CREDENTIALS");
        assertUnconsumed(challenge); assertThat(counter()).isZero(); assertThat(countSessions()).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_attempt_count FROM banking.app_user WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"revoked", "untrusted", "missingKey", "disabled", "foreignDevice"})
    void changedEligibilityAfterBeginFailsSecurely(String change) throws Exception {
        var challenge = begin();
        String ref = deviceRef;
        switch (change) {
            case "revoked" -> update("UPDATE banking.device SET status='REVOKED' WHERE device_id=?", deviceId);
            case "untrusted" -> update("UPDATE banking.device SET trusted=false WHERE device_id=?", deviceId);
            case "missingKey" -> update("UPDATE banking.device SET fido_public_key_cose=NULL WHERE device_id=?", deviceId);
            case "disabled" -> update("UPDATE banking.app_user SET status='DISABLED' WHERE user_id=?", userId);
            case "foreignDevice" -> deviceRef = UUID.randomUUID().toString();
        }
        var result = finish(challenge, sign(challenge, 1));
        assertProblem(result, 401, "AUTH_INVALID_CREDENTIALS");
        deviceRef = ref;
        assertThat(counter()).isZero(); assertThat(countSessions()).isZero(); assertUnconsumed(challenge);
    }

    @Test void foreignUserAndOtpChallengeCannotBeSubstituted() throws Exception {
        var challenge = begin(); var assertion = sign(challenge, 1);
        String owner = username;
        username = "unknown-" + UUID.randomUUID();
        assertProblem(finish(challenge, assertion), 404, "AUTH_CHALLENGE_NOT_FOUND");
        username = owner;
        update("UPDATE banking.auth_challenge SET method='OTP',channel='SMS',otp_hash='invalid' WHERE challenge_ref=CAST(? AS uuid)",
                challenge.path("challengeId").asText());
        assertProblem(finish(challenge, assertion), 409, "INVALID_STATE_TRANSITION");
        assertThat(countSessions()).isZero(); assertUnconsumed(challenge);
    }

    @Test void expiredAndUnknownChallengesDoNotAuthenticate() throws Exception {
        var challenge = begin();
        update("UPDATE banking.auth_challenge SET created_at=clock_timestamp()-interval '1 minute',expires_at=clock_timestamp()-interval '1 second' WHERE challenge_ref=CAST(? AS uuid)", challenge.path("challengeId").asText());
        assertProblem(finish(challenge, sign(challenge, 1)), 410, "AUTH_CHALLENGE_EXPIRED");
        var body = finishBody(challenge, sign(challenge, 1)); body.put("challengeId", UUID.randomUUID().toString());
        assertProblem(post(PATH, body), 404, "AUTH_CHALLENGE_NOT_FOUND");
        assertThat(countSessions()).isZero(); assertThat(counter()).isZero();
    }

    @Test void counterRollbackAndResetAreRejectedWithoutChangingCounter() throws Exception {
        update("UPDATE banking.device SET fido_sign_count=10 WHERE device_id=?", deviceId);
        for (long count : List.of(10L, 9L, 0L)) {
            var challenge = begin();
            assertProblem(finish(challenge, sign(challenge, count)), 401, "AUTH_INVALID_CREDENTIALS");
            assertUnconsumed(challenge);
        }
        assertThat(counter()).isEqualTo(10); assertThat(countSessions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.auth_attempt WHERE user_id=? AND reason_code='COUNTER_ANOMALY'",
                Integer.class, userId)).isEqualTo(3);
    }

    @Test void concurrentReplayHasExactlyOneCommittedAuthentication() throws Exception {
        var c = begin(); var a = sign(c, 1);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return finish(c, a); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return finish(c, a); });
            assertThat(List.of(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(countSessions()).isEqualTo(1); assertThat(counter()).isEqualTo(1);
    }

    @Test void concurrentDistinctChallengesCannotBypassCredentialCounter() throws Exception {
        var c1 = begin(); var c2 = begin();
        var a1 = sign(c1, 1); var a2 = sign(c2, 1);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return finish(c1, a1); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return finish(c2, a2); });
            assertThat(List.of(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 401);
        }
        assertThat(countSessions()).isEqualTo(1); assertThat(counter()).isEqualTo(1);
    }

    @Test void invalidAttemptsLockAccountAndCannotUsePasswordFallbackToBypassLock() throws Exception {
        var challenge = begin();
        for (int i = 1; i <= 5; i++) {
            var bad = authenticator.sign(challenge.path("fidoRequestOptions").path("challenge").asText(), RP,
                    "https://evil.example", 1);
            assertProblem(finish(challenge, bad), i == 5 ? 423 : 401,
                    i == 5 ? "AUTH_ACCOUNT_LOCKED" : "AUTH_INVALID_CREDENTIALS");
        }
        assertProblem(post("/api/v1/auth/login", Map.of("username", username, "password", PASSWORD)), 423, "AUTH_ACCOUNT_LOCKED");
        assertThat(countSessions()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref=CAST(? AS uuid)",
                String.class, challenge.path("challengeId").asText())).isEqualTo("LOCKED");
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "unavailable", "invalid", "expired", "noCredential", "counterAnomaly", "revoked"})
    void passwordFallbackRequiresFreshPasswordAndOtp(String scenario) throws Exception {
        if (!scenario.equals("noCredential")) {
            var challenge = begin();
            if (scenario.equals("invalid")) {
                var bad = authenticator.sign(challenge.path("fidoRequestOptions").path("challenge").asText(), RP,
                        "https://evil.example", 1);
                assertProblem(finish(challenge, bad), 401, "AUTH_INVALID_CREDENTIALS");
            } else if (scenario.equals("expired")) {
                update("UPDATE banking.auth_challenge SET created_at=clock_timestamp()-interval '1 minute',expires_at=clock_timestamp()-interval '1 second' WHERE challenge_ref=CAST(? AS uuid)", challenge.path("challengeId").asText());
                assertProblem(finish(challenge, sign(challenge, 1)), 410, "AUTH_CHALLENGE_EXPIRED");
            } else if (scenario.equals("counterAnomaly")) {
                update("UPDATE banking.device SET fido_sign_count=10 WHERE device_id=?", deviceId);
                assertProblem(finish(challenge, sign(challenge, 9)), 401, "AUTH_INVALID_CREDENTIALS");
            } else if (scenario.equals("revoked")) {
                update("UPDATE banking.device SET status='REVOKED' WHERE device_id=?", deviceId);
                assertProblem(finish(challenge, sign(challenge, 1)), 401, "AUTH_INVALID_CREDENTIALS");
            }
        } else {
            update("UPDATE banking.device SET fido_credential_id=NULL,fido_public_key_cose=NULL WHERE device_id=?", deviceId);
            assertProblem(post(PATH, Map.of("username", username, "deviceRef", deviceRef)), 401, "AUTH_INVALID_CREDENTIALS");
        }
        var login = post("/api/v1/auth/login", Map.of("username", username, "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(202); assertNoCookie(login); assertThat(countSessions()).isZero();
        String ref = JSON.readTree(login.body()).path("challenge").path("challengeId").asText();
        assertProblem(post("/api/v1/auth/otp/verify", Map.of("challengeId", ref, "otp", "00000000")), 401, "AUTH_OTP_INVALID");
        assertThat(countSessions()).isZero();
        char[] code = gateway.codes.get(ref);
        assertThat(code).isNotNull();
        var verified = post("/api/v1/auth/otp/verify", Map.of("challengeId", ref, "otp", new String(code)));
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(countSessions()).isEqualTo(1);
        assertThat(counter()).isEqualTo(scenario.equals("counterAnomaly") ? 10 : 0);
    }

    @Test void mandatoryAuditFailureRollsBackCounterConsumptionAndSession() throws Exception {
        var challenge = begin();
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit).recordInTransaction(any(), anyString());
        var result = finish(challenge, sign(challenge, 1));
        assertThat(result.statusCode()).isEqualTo(500); assertNoCookie(result);
        assertThat(counter()).isZero(); assertThat(countSessions()).isZero(); assertUnconsumed(challenge);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref=CAST(? AS uuid)",
                String.class, challenge.path("challengeId").asText())).isEqualTo("CREATED");
    }

    @Test void malformedRequestsCannotIssueSession() throws Exception {
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(),
                Map.of("username", username, "deviceRef", deviceRef, "challengeId", UUID.randomUUID().toString()),
                Map.of("username", username, "deviceRef", "not-a-uuid"),
                Map.of("username", username, "deviceRef", deviceRef, "fido", Map.of()))) {
            assertProblem(post(PATH, body), 400, "VALIDATION_ERROR");
        }
        assertThat(countSessions()).isZero();
    }

    private JsonNode begin() throws Exception {
        var result = post(PATH, Map.of("username", username, "deviceRef", deviceRef));
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(202);
        assertNoCookie(result);
        return JSON.readTree(result.body()).path("challenge");
    }
    private FidoAssertionVerifier.Assertion sign(JsonNode c, long count) {
        return authenticator.sign(c.path("fidoRequestOptions").path("challenge").asText(), RP, ORIGIN, count);
    }
    private Map<String, Object> finishBody(JsonNode c, FidoAssertionVerifier.Assertion a) {
        var body = new HashMap<String, Object>();
        body.put("username", username); body.put("deviceRef", deviceRef); body.put("challengeId", c.path("challengeId").asText());
        var fields = new HashMap<String, Object>();
        fields.put("credentialId", a.credentialId()); fields.put("clientDataJSON", a.clientDataJSON());
        fields.put("authenticatorData", a.authenticatorData()); fields.put("signature", a.signature());
        if (a.userHandle() != null) { fields.put("userHandle", a.userHandle()); }
        body.put("fido", fields); return body;
    }
    private HttpResponse<String> finish(JsonNode c, FidoAssertionVerifier.Assertion a) throws Exception { return post(PATH, finishBody(c, a)); }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    @Test void unsupportedAlgorithmCannotAuthenticateEvenWithMatchingCredentialId() throws Exception {
        var challenge = begin();
        update("UPDATE banking.device SET fido_public_key_cose=? WHERE device_id=?",
                authenticator.publicKeyCose(-65535), deviceId);
        assertProblem(finish(challenge, sign(challenge, 1)), 401, "AUTH_INVALID_CREDENTIALS");
        assertThat(counter()).isZero();
        assertThat(countSessions()).isZero();
        assertUnconsumed(challenge);
    }

    @Test void unavailableVerificationDependencyFailsClosedWithoutConsumingChallenge() throws Exception {
        var challenge = begin();
        doThrow(new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE))
                .when(verifier).verify(any(), anyString(), any());
        assertProblem(finish(challenge, sign(challenge, 1)), 503, "SERVICE_UNAVAILABLE");
        assertThat(counter()).isZero();
        assertThat(countSessions()).isZero();
        assertUnconsumed(challenge);
    }

    private void update(String sql, Object... arguments) {
        tx.executeWithoutResult(status -> jdbc.update(sql, arguments));
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private long counter() { return jdbc.queryForObject("SELECT fido_sign_count FROM banking.device WHERE device_id=?", Long.class, deviceId); }
    private int countSessions() { return jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id=?", Integer.class, userId); }
    private void assertUnconsumed(JsonNode c) {
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM banking.auth_challenge WHERE challenge_ref=CAST(? AS uuid)",
                Boolean.class, c.path("challengeId").asText())).isTrue();
    }
    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo(code);
        assertNoCookie(response);
        assertThat(response.body()).doesNotContain(PASSWORD, authenticator.publicKeyCose(), "fido_public_key_cose", "fido_nonce");
    }
    private void assertNoCookie(HttpResponse<String> response) {
        assertThat(response.headers().allValues("set-cookie")).isEmpty();
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
    }
    @TestConfiguration(proxyBeanMethods = false) static class Fixtures { @Bean Gateway fidoOtpGateway() { return new Gateway(); } }
    static class Gateway implements OtpGateway {
        final Map<String, char[]> codes = new ConcurrentHashMap<>();
        @Override public Result sendOtp(String ref, long userId, String channel, char[] otp, Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone()); return new Result(Outcome.ACCEPTED, Failure.NONE);
        }
    }
}
