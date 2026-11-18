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
import java.time.Instant;
import java.util.List;
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
 * BE-ADM-INT-01: HTTP evidence for the real Limit provider joined to the approval workflow.
 *
 * <p>Covers proposal (PENDING_APPROVAL configuration in the same transaction), approval (ACTIVE version applied
 * once), rejection, a second version, a stale expected version, unauthorized checker, policy rejection and the
 * C2 audit row/hash chain. PostgreSQL 16, security chain, Identity step-up, Limit and Audit are production code;
 * the OTP transport is the only test double.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class LimitApprovalHttpPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();
    private static final String PER_TX = "5000000.00";
    private static final String DAILY = "20000000.00";

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_limit");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("app.admin.ops.enabled", () -> "false");
        // OI-API-05 leaves numeric bounds unresolved: the test context pins a high bound so the
        // policy-422 case can be exercised deterministically without inventing a production default.
        registry.add("app.limit.policy.max-daily", () -> "100000000");
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
    private long auditor;
    private long customerId;
    private long accountId;
    private String makerToken;
    private String checkerToken;
    private String auditorToken;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.limit_config");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.approval_request");
        });
        maker = staff(jdbc, "adm-lim-maker-" + suffix, "OPERATIONS", false);
        checker = staff(jdbc, "adm-lim-checker-" + suffix, "ADMIN", true);
        auditor = staff(jdbc, "adm-lim-auditor-" + suffix, "AUDITOR", true);
        long customerUser = customerUser(jdbc, "adm-lim-customer-" + suffix);
        customerId = customer(jdbc, customerUser);
        accountId = account(jdbc, customerId);
        OTP_BY_CHALLENGE.clear();
        makerToken = session(jdbc, maker);
        checkerToken = session(jdbc, checker);
        auditorToken = session(jdbc, auditor);
    }

    @Test
    @DisplayName("INT-01: proposal persists approval + PENDING_APPROVAL limit and one C2 audit row")
    void proposalCreatesPendingConfigurationWithAudit(@Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = propose("adm-lim-key-1", "adm-lim-create-1");

        assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("PENDING");
        Map<String, Object> pending = jdbc.queryForMap(
                "SELECT status, version, per_transaction_limit, daily_limit FROM banking.limit_config "
                        + "WHERE approval_id = ?", approvalId);
        assertThat(pending.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("version")).isEqualTo(1);
        assertThat(pending.get("daily_limit").toString()).isEqualTo(DAILY);
        assertThat(auditCount(jdbc, approvalId, "LIMIT_CHANGE_REQUESTED")).isEqualTo(1);
        assertThat(auditChainIsLinked(jdbc, approvalId)).isTrue();
        // No effective limit exists before approval.
        assertThat(activeCount(jdbc)).isZero();
    }

    @Test
    @DisplayName("INT-01: approval activates the pending version once and records C2 evidence")
    void approvalActivatesOnceAndIsIdempotent(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        long approvalId = propose("adm-lim-key-2", "adm-lim-create-2");
        String challengeId = decisionChallenge(approvalId, checkerToken, "adm-lim-dec-2",
                "{\"decision\":\"APPROVE\",\"decisionNote\":\"within policy\"}");
        HttpResponse<String> applied = decide(approvalId, checkerToken, "adm-lim-200-2",
                "{\"decision\":\"APPROVE\",\"decisionNote\":\"within policy\"}", "adm-lim-key-2a", challengeId);

        assertThat(applied.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(applied.body());
        assertThat(body.get("status").asText()).isEqualTo("APPLIED");
        assertThat(body.get("result").get("version").asInt()).isEqualTo(1);
        assertThat(body.get("result").get("perTransactionLimit").asText()).isEqualTo(PER_TX);
        assertThat(body.toString()).doesNotContain("expectedVersion");

        Map<String, Object> active = jdbc.queryForMap(
                "SELECT status, version, approved_by FROM banking.limit_config WHERE approval_id = ?",
                approvalId);
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(active.get("version")).isEqualTo(1);
        assertThat(((Number) active.get("approved_by")).longValue()).isEqualTo(checker);
        assertThat(auditCount(jdbc, approvalId, "LIMIT_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(auditChainIsLinked(jdbc, approvalId)).isTrue();

        HttpResponse<String> replay = decide(approvalId, checkerToken, "adm-lim-replay-2",
                "{\"decision\":\"APPROVE\",\"decisionNote\":\"within policy\"}", "adm-lim-key-2a", challengeId);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(auditCount(jdbc, approvalId, "LIMIT_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(activeCount(jdbc)).isEqualTo(1);

        assertProblem(decide(approvalId, checkerToken, "adm-lim-dup-2", "{\"decision\":\"APPROVE\"}",
                "adm-lim-key-2b", challengeId), 409, "APPROVAL_ALREADY_DECIDED", "adm-lim-dup-2");
        assertThat(activeCount(jdbc)).isEqualTo(1);
        assertThat(output).contains("\"action\":\"LIMIT_CHANGE_APPROVED\"", "\"audit_class\":\"C2\"");
    }

    @Test
    @DisplayName("INT-01: a second approved change supersedes version 1 with version 2")
    void secondApprovalBumpsVersion(@Autowired JdbcTemplate jdbc) throws Exception {
        long first = propose("adm-lim-key-3a", "adm-lim-create-3a");
        approve(first, checkerToken, "adm-lim-dec-3a", "adm-lim-key-3a-dec");
        long second = propose("adm-lim-key-3b", "adm-lim-create-3b");
        approve(second, checkerToken, "adm-lim-dec-3b", "adm-lim-key-3b-dec");

        assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id = ?",
                String.class, first)).isEqualTo("SUPERSEDED");
        Map<String, Object> active = jdbc.queryForMap(
                "SELECT approval_id, version FROM banking.limit_config WHERE customer_id = ? "
                        + "AND account_id IS NULL AND status = 'ACTIVE'", customerId);
        assertThat(((Number) active.get("approval_id")).longValue()).isEqualTo(second);
        assertThat(active.get("version")).isEqualTo(2);
    }

    @Test
    @DisplayName("INT-01: a stale expected version is 409 ROLE_CONFLICT and never applies")
    void staleExpectedVersionIsRejected(@Autowired JdbcTemplate jdbc) throws Exception {
        long first = propose("adm-lim-key-4a", "adm-lim-create-4a");
        approve(first, checkerToken, "adm-lim-dec-4a", "adm-lim-key-4a-dec");
        long second = propose("adm-lim-key-4b", "adm-lim-create-4b");

        // Out-of-band change after the step-up-proof was issued: version moves on.
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.limit_config SET version = version + 100 "
                        + "WHERE customer_id = ? AND status = 'ACTIVE'", customerId));

        String challengeId = decisionChallenge(second, checkerToken, "adm-lim-dec-4b",
                "{\"decision\":\"APPROVE\"}");
        assertProblem(decide(second, checkerToken, "adm-lim-stale-4b",
                "{\"decision\":\"APPROVE\"}", "adm-lim-key-4b-dec", challengeId), 409, "ROLE_CONFLICT",
                "adm-lim-stale-4b");

        assertThat(approvalStatus(jdbc, second)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.limit_config WHERE approval_id = ?", String.class, second))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM banking.limit_config WHERE customer_id = ? AND status = 'ACTIVE'",
                Integer.class, customerId)).isEqualTo(101);
        assertThat(auditCount(jdbc, second, "LIMIT_CHANGE_APPROVED")).isZero();
        assertThat(attempts(jdbc, second, "VERSION_CONFLICT")).isEqualTo(1);

        // Recovery: restore the expected version and retry with the SAME key/challenge (the failed
        // transaction rolled the challenge consumption back). The change is applied exactly once.
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.limit_config SET version = 1 "
                        + "WHERE customer_id = ? AND status = 'ACTIVE'", customerId));
        HttpResponse<String> recovered = decide(second, checkerToken, "adm-lim-recovered-4b",
                "{\"decision\":\"APPROVE\"}", "adm-lim-key-4b-dec", challengeId);
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        assertThat(approvalStatus(jdbc, second)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM banking.limit_config WHERE approval_id = ?", Integer.class, second))
                .isEqualTo(2);
        assertThat(auditCount(jdbc, second, "LIMIT_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(attempts(jdbc, second, "VERSION_CONFLICT")).isEqualTo(1);
    }

    @Test
    @DisplayName("INT-01: rejection marks the pending configuration REJECTED and keeps the effective limit")
    void rejectionMarksPendingConfigurationRejected(@Autowired JdbcTemplate jdbc) throws Exception {
        long first = propose("adm-lim-key-5a", "adm-lim-create-5a");
        approve(first, checkerToken, "adm-lim-dec-5a", "adm-lim-key-5a-dec");
        long second = propose("adm-lim-key-5b", "adm-lim-create-5b");

        String challengeId = decisionChallenge(second, checkerToken, "adm-lim-dec-5b",
                "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}");
        HttpResponse<String> rejected = decide(second, checkerToken, "adm-lim-rej-5b",
                "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}", "adm-lim-key-5b-dec",
                challengeId);

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(rejected.body()).get("status").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id = ?",
                String.class, second)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT version FROM banking.limit_config WHERE status = 'ACTIVE'",
                Integer.class)).isEqualTo(1);
        assertThat(auditCount(jdbc, second, "LIMIT_CHANGE_REJECTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("INT-01: a checker without the Limit permission is denied before any state change")
    void checkerWithoutLimitPermissionIsForbidden(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        long approvalId = propose("adm-lim-key-6", "adm-lim-create-6");

        assertProblem(decide(approvalId, auditorToken, "adm-lim-forbidden-6", "{\"decision\":\"APPROVE\"}",
                "adm-lim-key-6-dec", null), 403, "AUTH_FORBIDDEN", "adm-lim-forbidden-6");

        assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id = ?",
                String.class, approvalId)).isEqualTo("PENDING_APPROVAL");
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @DisplayName("INT-01: a configured policy bound rejects the proposal with 422 and no state")
    void policyViolationCreatesNoState(@Autowired JdbcTemplate jdbc) throws Exception {
        String body = limitBody("200000000.00", "300000000.00");
        HttpResponse<String> challengeRequired = call("/api/v1/admin/limits", makerToken,
                "adm-lim-policy-428", body, "adm-lim-key-7", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", "adm-lim-policy-428");
        String challengeId = challengeId(challengeRequired);
        verify(makerToken, challengeId, "adm-lim-policy-verify");

        assertProblem(call("/api/v1/admin/limits", makerToken, "adm-lim-policy-422", body, "adm-lim-key-7",
                challengeId), 422, "LIMIT_OUT_OF_POLICY", "adm-lim-policy-422");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.approval_request", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Long.class)).isZero();
    }

    @Test
    @DisplayName("INT-01: a durable audit C2 failure rolls back the limit change and retries once")
    void auditFailureRollsBackAndRetryAppliesOnce(@Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = propose("adm-lim-key-8", "adm-lim-create-8");
        String challengeId = decisionChallenge(approvalId, checkerToken, "adm-lim-dec-8",
                "{\"decision\":\"APPROVE\"}");
        tx.executeWithoutResult(ignored -> jdbc.execute("""
                CREATE OR REPLACE FUNCTION banking.synthetic_fail_audit_insert() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic audit failure' USING ERRCODE = '58000';
                END;
                $$
                """));
        tx.executeWithoutResult(ignored -> jdbc.execute(
                "CREATE TRIGGER trg_synthetic_fail_audit_insert BEFORE INSERT ON banking.audit_log "
                        + "FOR EACH ROW EXECUTE FUNCTION banking.synthetic_fail_audit_insert()"));
        try {
            HttpResponse<String> failed = decide(approvalId, checkerToken, "adm-lim-audit-8",
                    "{\"decision\":\"APPROVE\"}", "adm-lim-key-8-dec", challengeId);

            assertProblem(failed, 500, "AUDIT_WRITE_FAILED", "adm-lim-audit-8");
            assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id = ?",
                    String.class, approvalId)).isEqualTo("PENDING_APPROVAL");
            assertThat(activeCount(jdbc)).isZero();
            assertThat(attempts(jdbc, approvalId, "AUDIT_FAILURE")).isEqualTo(1);
        } finally {
            tx.executeWithoutResult(ignored -> {
                jdbc.execute("DROP TRIGGER IF EXISTS trg_synthetic_fail_audit_insert ON banking.audit_log");
                jdbc.execute("DROP FUNCTION IF EXISTS banking.synthetic_fail_audit_insert()");
            });
        }

        HttpResponse<String> retried = decide(approvalId, checkerToken, "adm-lim-audit-retry-8",
                "{\"decision\":\"APPROVE\"}", "adm-lim-key-8-dec", challengeId);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("APPLIED");
        assertThat(auditCount(jdbc, approvalId, "LIMIT_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(activeCount(jdbc)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ flow helpers

    private long propose(String idempotencyKey, String correlationId) throws Exception {
        String body = limitBody(PER_TX, DAILY);
        HttpResponse<String> challengeRequired = call("/api/v1/admin/limits", makerToken, correlationId + "-428",
                body, idempotencyKey, null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlationId + "-428");
        String challengeId = challengeId(challengeRequired);
        verify(makerToken, challengeId, correlationId + "-verify");
        HttpResponse<String> created = call("/api/v1/admin/limits", makerToken, correlationId, body,
                idempotencyKey, challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).startsWith("/api/v1/admin/approvals/"));
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private void approve(long approvalId, String token, String correlationId, String key) throws Exception {
        String challengeId = decisionChallenge(approvalId, token, correlationId, "{\"decision\":\"APPROVE\"}");
        HttpResponse<String> applied = decide(approvalId, token, correlationId + "-200",
                "{\"decision\":\"APPROVE\"}", key, challengeId);
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
    }

    private String decisionChallenge(long approvalId, String token, String correlationId, String body)
            throws Exception {
        String challengeId = challengeId(call("/api/v1/admin/limits/" + approvalId + "/approve", token,
                correlationId + "-428", body, correlationId + "-key", null));
        verify(token, challengeId, correlationId + "-verify");
        return challengeId;
    }

    private String limitBody(String perTransactionLimit, String dailyLimit) {
        return "{\"customerId\":\"" + customerId + "\",\"perTransactionLimit\":\"" + perTransactionLimit
                + "\",\"dailyLimit\":\"" + dailyLimit + "\",\"currency\":\"VND\",\"reason\":\"synthetic limit\"}";
    }

    private void verify(String token, String challengeId, String correlationId) throws Exception {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("OTP captured for " + challengeId).isNotNull();
        HttpResponse<String> verified = call("/api/v1/auth/step-up/verify", token, correlationId,
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null, null);
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
    }

    private static String challengeId(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("challenge").get("challengeId").asText();
    }

    private HttpResponse<String> decide(long approvalId, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        return call("/api/v1/admin/limits/" + approvalId + "/approve", token, correlationId, body,
                idempotencyKey, stepUpChallenge);
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

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("detail").asText()).doesNotContain("Exception", "PSQLException", "banking.");
    }

    // ------------------------------------------------------------------ state helpers

    private static String approvalStatus(JdbcTemplate jdbc, long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private static int activeCount(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.limit_config WHERE status = 'ACTIVE'", Integer.class);
        return count == null ? 0 : count;
    }

    private static int auditCount(JdbcTemplate jdbc, long approvalId, String action) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.audit_log WHERE entity_type = 'APPROVAL_REQUEST' "
                        + "AND entity_id = ? AND action = ?", Integer.class, Long.toString(approvalId), action);
        return count == null ? 0 : count;
    }

    /** Hash chain integrity for the rows of one approval: each row links to the previous chain record. */
    private static boolean auditChainIsLinked(JdbcTemplate jdbc, long approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT audit_id, prev_hash, record_hash FROM banking.audit_log "
                        + "WHERE entity_type = 'APPROVAL_REQUEST' AND entity_id = ? ORDER BY audit_id",
                Long.toString(approvalId));
        for (int index = 1; index < rows.size(); index++) {
            String previousHash = (String) jdbc.queryForObject(
                    "SELECT record_hash FROM banking.audit_log WHERE audit_id < ? ORDER BY audit_id DESC LIMIT 1",
                    String.class, rows.get(index).get("audit_id"));
            if (!previousHash.equals(rows.get(index).get("prev_hash"))) {
                return false;
            }
        }
        for (Map<String, Object> row : rows) {
            String hash = (String) row.get("record_hash");
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                return false;
            }
        }
        return true;
    }

    private static int attempts(JdbcTemplate jdbc, long approvalId, String outcome) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_approval_attempt WHERE approval_id = ? AND outcome = ?",
                Integer.class, approvalId, outcome);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ fixtures

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

    private long customerUser(JdbcTemplate jdbc, String username) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'CUSTOMER', 'ACTIVE')", username);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", userId);
            return userId;
        });
    }

    private long customer(JdbcTemplate jdbc, long userId) {
        return tx.execute(ignored -> jdbc.queryForObject(
                "INSERT INTO banking.customer (user_id, full_name_enc, status) VALUES (?, ?, 'ACTIVE') "
                        + "RETURNING customer_id",
                Long.class, userId, new byte[] {1, 2, 3}));
    }

    private long account(JdbcTemplate jdbc, long customerId) {
        String hash = HmacService.sha256Hex("lim-" + UUID.randomUUID());
        return tx.execute(ignored -> jdbc.queryForObject(
                "INSERT INTO banking.account (customer_id, account_no_enc, account_no_hash, account_no_last4) "
                        + "VALUES (?, ?, ?, '1234') RETURNING account_id",
                Long.class, customerId, new byte[] {4, 5, 6}, hash));
    }

    private String session(JdbcTemplate jdbc, long userId) {
        String token = "adm-lim-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }
}
