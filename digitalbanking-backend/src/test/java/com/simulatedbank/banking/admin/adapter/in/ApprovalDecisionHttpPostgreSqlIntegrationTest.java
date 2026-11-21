package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-API-02: HTTP evidence for the checker decision endpoints on the real security chain, Step-Up
 * challenge flow, Identity role provider and PostgreSQL schema.
 *
 * <p>Only the OTP transport is a test double (notification provider absent); challenge state, hashing, consume,
 * approval lock/transition, identity mutation and C2 audit are production code.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class ApprovalDecisionHttpPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_decision");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @TestConfiguration
    static class OtpTransport {
        @Bean
        OtpGateway capturingOtpGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) -> {
                OTP_BY_CHALLENGE.put(ref, new String(otp));
                return new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long otherChecker;
    private long target;
    private String makerToken;
    private String checkerToken;
    private String otherCheckerToken;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
        maker = staff(jdbc, "adm-dec-maker-" + suffix, "ADMIN", true);
        checker = staff(jdbc, "adm-dec-checker-" + suffix, "ADMIN", true);
        otherChecker = staff(jdbc, "adm-dec-checker2-" + suffix, "ADMIN", true);
        target = staff(jdbc, "adm-dec-target-" + suffix, "OPERATIONS", false);
        OTP_BY_CHALLENGE.clear();
        makerToken = session(jdbc, maker);
        checkerToken = session(jdbc, checker);
        otherCheckerToken = session(jdbc, otherChecker);
    }

    @Test
    @DisplayName("API-02: verified checker step-up applies the role once and returns 200 APPLIED")
    void approveReturnsAppliedAndIsIdempotent(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = proposeRole("adm-dec-key-1", "adm-dec-create-1");

        HttpResponse<String> challengeRequired = call("/api/v1/admin/roles/" + approvalId + "/approve",
                checkerToken, "adm-dec-428", "{\"decision\":\"APPROVE\",\"decisionNote\":\"approved\"}",
                "adm-dec-decide-1", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", "adm-dec-428");
        String challengeId = challengeId(challengeRequired);
        verify(checkerToken, challengeId, "adm-dec-verify-1");

        HttpResponse<String> applied = call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "adm-dec-200", "{\"decision\":\"APPROVE\",\"decisionNote\":\"approved\"}",
                "adm-dec-decide-1", challengeId);

        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(applied.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(applied.body());
        assertThat(body.get("approvalId").asText()).isEqualTo(Long.toString(approvalId));
        assertThat(body.get("status").asText()).isEqualTo("APPLIED");
        assertThat(body.get("checker").get("userId").asText()).isEqualTo(Long.toString(checker));
        assertThat(body.get("appliedAt").asText()).isNotBlank();
        assertThat(body.get("payload").has("expectedVersion")).isFalse();
        assertThat(body.get("payload").get("targetUserId").asText()).isEqualTo(Long.toString(target));
        assertThat(body.get("result").get("operation").asText()).isEqualTo("ASSIGN_ROLE");
        assertThat(body.get("result").get("roleCode").asText()).isEqualTo("AUDITOR");
        assertThat(applied.body()).doesNotContain("expectedVersion", "synthetic-not-a-real-hash");
        assertThat(roleCount(jdbc, target)).isEqualTo(1);
        assertThat(output).contains("\"action\":\"ROLE_CHANGE_APPROVED\"", "\"audit_class\":\"C2\"");

        HttpResponse<String> replay = call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "adm-dec-replay", "{\"decision\":\"APPROVE\",\"decisionNote\":\"approved\"}",
                "adm-dec-decide-1", challengeId);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body()).get("status").asText()).isEqualTo("APPLIED");
        assertThat(roleCount(jdbc, target)).isEqualTo(1);
        assertThat(output.toString().split("\"action\":\"ROLE_CHANGE_APPROVED\"", -1)).hasSize(2);

        // A different key on a terminal approval is a conflict, never a second apply.
        assertProblem(call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken, "adm-dec-dup",
                "{\"decision\":\"APPROVE\"}", "adm-dec-decide-other", challengeId), 409,
                "APPROVAL_ALREADY_DECIDED", "adm-dec-dup");
        assertThat(roleCount(jdbc, target)).isEqualTo(1);
    }

    @Test
    @DisplayName("API-02: rejection returns 200 REJECTED, keeps the provider untouched and records C2")
    void rejectReturnsRejectedWithoutProviderMutation(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        long approvalId = proposeRole("adm-dec-key-2", "adm-dec-create-2");
        String challengeId = challengeId(call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "adm-dec-rej-428", "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}",
                "adm-dec-reject-1", null));
        verify(checkerToken, challengeId, "adm-dec-rej-verify");

        HttpResponse<String> rejected = call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "adm-dec-rej-200", "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}",
                "adm-dec-reject-1", challengeId);

        assertThat(rejected.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(rejected.body());
        assertThat(body.get("status").asText()).isEqualTo("REJECTED");
        assertThat(body.get("decidedAt").asText()).isNotBlank();
        assertThat(body.get("appliedAt").isNull()).isTrue();
        assertThat(body.get("decisionNote").asText()).isEqualTo("outside policy");
        assertThat(body.has("result")).isFalse();
        assertThat(roleCount(jdbc, target)).isZero();
        assertThat(output).contains("\"action\":\"ROLE_CHANGE_REJECTED\"", "\"audit_class\":\"C2\"");
    }

    @Test
    @DisplayName("API-02: a maker cannot decide their own request even with CHECKER privilege")
    void makerSelfDecisionIsDenied(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = proposeRole("adm-dec-key-3", "adm-dec-create-3");
        // The maker also holds CHECKER, so the denial must come from the Maker–Checker rule before a challenge.
        assertProblem(call("/api/v1/admin/roles/" + approvalId + "/approve", makerToken, "adm-dec-self-409",
                "{\"decision\":\"APPROVE\"}", "adm-dec-self-1", null), 409, "ROLE_CONFLICT",
                "adm-dec-self-409");

        assertThat(status(jdbc, approvalId)).isEqualTo("PENDING");
        assertThat(roleCount(jdbc, target)).isZero();
        assertThat(output).doesNotContain("\"action\":\"ROLE_CHANGE_APPROVED\"");
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"",
                "\"reason_code\":\"ROLE_CONFLICT\"");
    }

    @Test
    @DisplayName("API-02: a challenge bound to another approval is rejected without state change")
    void challengeBoundToAnotherApprovalIsRejected(@Autowired JdbcTemplate jdbc) throws Exception {
        long first = proposeRole("adm-dec-key-4a", "adm-dec-create-4a");
        long second = proposeRole("adm-dec-key-4b", "adm-dec-create-4b");
        String firstChallenge = challengeId(call("/api/v1/admin/roles/" + first + "/approve", checkerToken,
                "adm-dec-bind-428", "{\"decision\":\"APPROVE\"}", "adm-dec-bind-1", null));
        verify(checkerToken, firstChallenge, "adm-dec-bind-verify");

        HttpResponse<String> mismatch = call("/api/v1/admin/roles/" + second + "/approve", checkerToken,
                "adm-dec-bind-409", "{\"decision\":\"APPROVE\"}", "adm-dec-bind-2", firstChallenge);

        assertThat(mismatch.statusCode()).isIn(404, 409);
        assertThat(JSON.readTree(mismatch.body()).get("code").asText())
                .isIn("AUTH_CHALLENGE_NOT_FOUND", "STEP_UP_CHALLENGE_MISMATCH", "INVALID_STATE_TRANSITION");
        assertThat(status(jdbc, first)).isEqualTo("PENDING");
        assertThat(status(jdbc, second)).isEqualTo("PENDING");
        assertThat(roleCount(jdbc, target)).isZero();
    }

    @Test
    @DisplayName("API-02: a checker lacking the type permission is denied before any challenge or lock")
    void outOfScopeCheckerIsDenied(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        long limitOnlyChecker = tx.execute(ignored -> {
            String username = "adm-dec-limit-checker-" + UUID.randomUUID();
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'OPERATIONS'", userId);
            jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                    + "VALUES (?, 'CHECKER', 'ACTIVE')", userId);
            return userId;
        });
        String limitCheckerToken = session(jdbc, limitOnlyChecker);
        long approvalId = proposeRole("adm-dec-key-5", "adm-dec-create-5");

        assertProblem(call("/api/v1/admin/roles/" + approvalId + "/approve", limitCheckerToken, "adm-dec-scope",
                "{\"decision\":\"APPROVE\"}", "adm-dec-scope-1", null), 403, "AUTH_FORBIDDEN", "adm-dec-scope");
        assertThat(status(jdbc, approvalId)).isEqualTo("PENDING");
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"", "\"entity_type\":\"APPROVAL_REQUEST\"");
    }

    @Test
    @DisplayName("API-02: concurrent checkers produce exactly one APPLIED winner and one conflict")
    void concurrentCheckerDecisionsHaveOneWinner(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        long approvalId = proposeRole("adm-dec-key-6", "adm-dec-create-6");
        String firstChallenge = decisionChallenge(approvalId, checkerToken, "adm-dec-conc-1");
        String secondChallenge = decisionChallenge(approvalId, otherCheckerToken, "adm-dec-conc-2");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken, "adm-dec-conc-a",
                        "{\"decision\":\"APPROVE\"}", "adm-dec-conc-key-a", firstChallenge);
            });
            var second = pool.submit(() -> {
                start.await();
                return call("/api/v1/admin/roles/" + approvalId + "/approve", otherCheckerToken, "adm-dec-conc-b",
                        "{\"decision\":\"APPROVE\"}", "adm-dec-conc-key-b", secondChallenge);
            });
            start.countDown();
            int firstStatus = first.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode();
            int secondStatus = second.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode();

            assertThat(java.util.List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(jdbc, approvalId)).isEqualTo("APPLIED");
        assertThat(roleCount(jdbc, target)).isEqualTo(1);
        assertThat(output.toString().split("\"action\":\"ROLE_CHANGE_APPROVED\"", -1)).hasSize(2);
    }

    @Test
    @DisplayName("INT-01: role approval revokes the target's active sessions")
    void roleApprovalRevokesTargetSessions(@Autowired JdbcTemplate jdbc) throws Exception {
        String targetToken = session(jdbc, target);
        long approvalId = proposeRole("adm-dec-key-sess", "adm-dec-create-sess");
        String challengeId = decisionChallenge(approvalId, checkerToken, "adm-dec-sess");

        HttpResponse<String> applied = call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "adm-dec-sess-200", "{\"decision\":\"APPROVE\"}", "adm-dec-sess-decide", challengeId);

        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        assertThat(JSON.readTree(applied.body()).get("result").get("revokedSessionCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                HmacService.sha256Hex(targetToken))).isEqualTo("LOGGED_OUT");
        // The revoked session cannot keep using the privileges it held before the role change.
        assertProblem(get("/api/v1/admin/approvals", targetToken, "adm-dec-sess-after"), 401,
                "AUTH_SESSION_EXPIRED", "adm-dec-sess-after");
    }

    @Test
    @DisplayName("INT-01: a session revocation failure rolls the role change back")
    void sessionRevocationFailureRollsBackTheRoleChange(@Autowired JdbcTemplate jdbc) throws Exception {
        String targetToken = session(jdbc, target);
        tx.executeWithoutResult(ignored -> jdbc.execute("""
                CREATE OR REPLACE FUNCTION banking.synthetic_fail_session_revoke() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id = %d AND OLD.status = 'ACTIVE' AND NEW.status = 'LOGGED_OUT' THEN
                        RAISE EXCEPTION 'synthetic revoke failure' USING ERRCODE = '58000';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(target)));
        tx.executeWithoutResult(ignored -> jdbc.execute(
                "CREATE TRIGGER trg_synthetic_fail_session_revoke BEFORE UPDATE ON banking.session "
                        + "FOR EACH ROW EXECUTE FUNCTION banking.synthetic_fail_session_revoke()"));
        try {
            long approvalId = proposeRole("adm-dec-key-revoke", "adm-dec-create-revoke");
            String challengeId = decisionChallenge(approvalId, checkerToken, "adm-dec-revoke");

            HttpResponse<String> failed = call("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                    "adm-dec-revoke-503", "{\"decision\":\"APPROVE\"}", "adm-dec-revoke-decide", challengeId);

            // A generic DB failure (SQLState 58000) is an internal technical failure, not a retryable
            // provider/lock timeout; the approval and the target sessions must stay untouched.
            assertProblem(failed, 500, "INTERNAL_ERROR", "adm-dec-revoke-503");
            assertThat(status(jdbc, approvalId)).isEqualTo("PENDING");
            assertThat(roleCount(jdbc, target)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM banking.session WHERE session_token_hash = ?", String.class,
                    HmacService.sha256Hex(targetToken))).isEqualTo("ACTIVE");
        } finally {
            tx.executeWithoutResult(ignored -> {
                jdbc.execute("DROP TRIGGER IF EXISTS trg_synthetic_fail_session_revoke ON banking.session");
                jdbc.execute("DROP FUNCTION IF EXISTS banking.synthetic_fail_session_revoke()");
            });
        }
    }

    // ------------------------------------------------------------------ flow helpers

    private String decisionChallenge(long approvalId, String token, String correlationId) throws Exception {
        String challengeId = challengeId(call("/api/v1/admin/roles/" + approvalId + "/approve", token,
                correlationId + "-428", "{\"decision\":\"APPROVE\"}", correlationId + "-key", null));
        verify(token, challengeId, correlationId + "-verify");
        return challengeId;
    }

    private long proposeRole(String idempotencyKey, String correlationId) throws Exception {
        String body = roleBody();
        HttpResponse<String> challengeRequired = call("/api/v1/admin/roles", makerToken, correlationId + "-428",
                body, idempotencyKey, null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlationId + "-428");
        String challengeId = challengeId(challengeRequired);
        verify(makerToken, challengeId, correlationId + "-verify");
        HttpResponse<String> created = call("/api/v1/admin/roles", makerToken, correlationId, body,
                idempotencyKey, challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private String roleBody() {
        return "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"synthetic decision flow\"}";
    }

    private void verify(String token, String challengeId, String correlationId) throws Exception {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("OTP captured for " + challengeId).isNotNull();
        HttpResponse<String> verified = call("/api/v1/auth/step-up/verify", token, correlationId,
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null, null);
        assertThat(verified.statusCode()).isEqualTo(200);
    }

    private static String challengeId(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("challenge").get("challengeId").asText();
    }

    private HttpResponse<String> call(String path, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Correlation-ID", correlationId)
                .header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (stepUpChallenge != null) {
            builder.header("X-Step-Up-Challenge", stepUpChallenge);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Correlation-ID", correlationId)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("detail").asText()).doesNotContain("Exception", "PSQLException", "banking.");
    }

    private static long roleCount(JdbcTemplate jdbc, long userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM banking.user_role ur JOIN banking.role r "
                + "ON r.role_id = ur.role_id WHERE ur.user_id = ? AND r.role_code = 'AUDITOR'", Long.class, userId);
        return count == null ? 0 : count;
    }

    private static String status(JdbcTemplate jdbc, long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private long staff(JdbcTemplate jdbc, String username, String roleCode, boolean checkerPrivilege) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            if (checkerPrivilege) {
                jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", userId);
            }
            return userId;
        });
    }

    private String session(JdbcTemplate jdbc, long userId) {
        String token = "adm-dec-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }
}
