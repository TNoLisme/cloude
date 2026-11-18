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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * BE-ADM-REL-01: acceptance evidence for {@code AC-028} on the real HTTP security chain, PostgreSQL 16 schema,
 * Identity step-up/challenge and Limit provider.
 *
 * <p>AC-028 (docs-common/03 §10.1): an Admin Maker proposes a role/limit change; a Checker approves and the
 * change is applied, or rejects and nothing is applied; the Maker cannot also be the Checker; the decision is
 * audited. Each test below maps to one clause and asserts both the provider state and the durable audit row, so a
 * green suite is direct acceptance evidence rather than a restatement of unit mocks.</p>
 *
 * <p>Only the OTP transport is a test double (no notification provider in the MVP); challenge state, approval
 * lock/transition, Identity role apply, Limit version apply and C2 audit are production code.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AdminAc028AcceptancePostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();
    private static final String PER_TX = "5000000.00";
    private static final String DAILY = "20000000.00";

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_ac028");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("app.admin.ops.enabled", () -> "false");
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
    private long roleMaker;
    private long checker;
    private long makerChecker;
    private long limitMaker;
    private long target;
    private long customerId;
    private String roleMakerToken;
    private String checkerToken;
    private String makerCheckerToken;
    private String limitMakerToken;

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
        roleMaker = staff(jdbc, "ac028-role-maker-" + suffix, "ADMIN", false);
        checker = staff(jdbc, "ac028-checker-" + suffix, "ADMIN", true);
        makerChecker = staff(jdbc, "ac028-maker-checker-" + suffix, "ADMIN", true);
        limitMaker = staff(jdbc, "ac028-limit-maker-" + suffix, "OPERATIONS", false);
        target = staff(jdbc, "ac028-target-" + suffix, "OPERATIONS", false);
        customerId = customer(jdbc, customerUser(jdbc, "ac028-customer-" + suffix));
        OTP_BY_CHALLENGE.clear();
        roleMakerToken = session(jdbc, roleMaker);
        checkerToken = session(jdbc, checker);
        makerCheckerToken = session(jdbc, makerChecker);
        limitMakerToken = session(jdbc, limitMaker);
    }

    @Test
    @DisplayName("AC-028: Checker approval applies the role/privilege change")
    void checkerApprovalAppliesTheRoleChange(@Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = proposeRole(roleMakerToken, "ac028-role-approve");

        HttpResponse<String> applied = decideRole(approvalId, checkerToken, "ac028-role-approve-decide",
                "{\"decision\":\"APPROVE\",\"decisionNote\":\"within policy\"}");

        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(applied.body());
        assertThat(body.get("status").asText()).isEqualTo("APPLIED");
        assertThat(body.get("result").get("roleCode").asText()).isEqualTo("AUDITOR");
        assertThat(roleCount(jdbc, target)).isEqualTo(1);
        assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("APPLIED");
        assertThat(auditCount(jdbc, approvalId, "ROLE_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(auditChainIsLinked(jdbc)).isTrue();
    }

    @Test
    @DisplayName("AC-028: Checker approval applies the limit change")
    void checkerApprovalAppliesTheLimitChange(@Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = proposeLimit("ac028-limit-approve");

        HttpResponse<String> applied = decideLimit(approvalId, checkerToken, "ac028-limit-approve-decide",
                "{\"decision\":\"APPROVE\",\"decisionNote\":\"within policy\"}");

        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(applied.body());
        assertThat(body.get("status").asText()).isEqualTo("APPLIED");
        assertThat(body.get("result").get("version").asInt()).isEqualTo(1);
        Map<String, Object> active = jdbc.queryForMap(
                "SELECT status, version FROM banking.limit_config WHERE approval_id = ?", approvalId);
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(active.get("version")).isEqualTo(1);
        assertThat(activeLimitCount(jdbc)).isEqualTo(1);
        assertThat(auditCount(jdbc, approvalId, "LIMIT_CHANGE_APPROVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-028: Checker rejection applies neither the role nor the limit change")
    void checkerRejectionAppliesNoChange(@Autowired JdbcTemplate jdbc) throws Exception {
        long roleApproval = proposeRole(roleMakerToken, "ac028-role-reject");
        HttpResponse<String> rejectedRole = decideRole(roleApproval, checkerToken, "ac028-role-reject-decide",
                "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}");

        assertThat(rejectedRole.statusCode()).as(rejectedRole.body()).isEqualTo(200);
        assertThat(JSON.readTree(rejectedRole.body()).get("status").asText()).isEqualTo("REJECTED");
        assertThat(roleCount(jdbc, target)).isZero();
        assertThat(approvalStatus(jdbc, roleApproval)).isEqualTo("REJECTED");
        assertThat(auditCount(jdbc, roleApproval, "ROLE_CHANGE_REJECTED")).isEqualTo(1);
        assertThat(auditCount(jdbc, roleApproval, "ROLE_CHANGE_APPROVED")).isZero();

        long limitApproval = proposeLimit("ac028-limit-reject");
        HttpResponse<String> rejectedLimit = decideLimit(limitApproval, checkerToken, "ac028-limit-reject-decide",
                "{\"decision\":\"REJECT\",\"decisionNote\":\"outside policy\"}");

        assertThat(rejectedLimit.statusCode()).as(rejectedLimit.body()).isEqualTo(200);
        assertThat(JSON.readTree(rejectedLimit.body()).get("status").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id = ?",
                String.class, limitApproval)).isEqualTo("REJECTED");
        assertThat(activeLimitCount(jdbc)).isZero();
        assertThat(auditCount(jdbc, limitApproval, "LIMIT_CHANGE_REJECTED")).isEqualTo(1);
        assertThat(auditCount(jdbc, limitApproval, "LIMIT_CHANGE_APPROVED")).isZero();
    }

    @Test
    @DisplayName("AC-028: the Maker cannot decide their own request, even when holding CHECKER")
    void makerCannotBeTheChecker(@Autowired JdbcTemplate jdbc) throws Exception {
        long approvalId = proposeRole(makerCheckerToken, "ac028-self");

        HttpResponse<String> denied = call("/api/v1/admin/roles/" + approvalId + "/approve", makerCheckerToken,
                "ac028-self-409", "{\"decision\":\"APPROVE\"}", "ac028-self-decide", null);

        assertProblem(denied, 409, "ROLE_CONFLICT", "ac028-self-409");
        assertThat(approvalStatus(jdbc, approvalId)).isEqualTo("PENDING");
        assertThat(roleCount(jdbc, target)).isZero();
        assertThat(auditCount(jdbc, approvalId, "ROLE_CHANGE_APPROVED")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE action = "
                + "'SECURITY_ACCESS_DENIED' AND entity_type = 'APPROVAL_REQUEST' AND entity_id = ? "
                + "AND outcome = 'REJECTED'", Integer.class, Long.toString(approvalId))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ flows

    private long proposeRole(String token, String correlationId) throws Exception {
        String body = "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"synthetic AC-028\"}";
        return propose("/api/v1/admin/roles", token, body, correlationId);
    }

    private long proposeLimit(String correlationId) throws Exception {
        String body = "{\"customerId\":\"" + customerId + "\",\"perTransactionLimit\":\"" + PER_TX
                + "\",\"dailyLimit\":\"" + DAILY + "\",\"currency\":\"VND\",\"reason\":\"synthetic AC-028\"}";
        return propose("/api/v1/admin/limits", limitMakerToken, body, correlationId);
    }

    private long propose(String path, String token, String body, String correlationId) throws Exception {
        HttpResponse<String> challengeRequired = call(path, token, correlationId + "-428", body,
                correlationId + "-key", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlationId + "-428");
        String challengeId = challengeId(challengeRequired);
        verify(token, challengeId, correlationId + "-verify");
        HttpResponse<String> created = call(path, token, correlationId, body, correlationId + "-key",
                challengeId);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private HttpResponse<String> decideRole(long approvalId, String token, String correlationId, String body)
            throws Exception {
        String challengeId = decisionChallenge("/api/v1/admin/roles/" + approvalId + "/approve", token, body,
                correlationId);
        return call("/api/v1/admin/roles/" + approvalId + "/approve", token, correlationId + "-200", body,
                correlationId + "-key", challengeId);
    }

    private HttpResponse<String> decideLimit(long approvalId, String token, String correlationId, String body)
            throws Exception {
        String challengeId = decisionChallenge("/api/v1/admin/limits/" + approvalId + "/approve", token, body,
                correlationId);
        return call("/api/v1/admin/limits/" + approvalId + "/approve", token, correlationId + "-200", body,
                correlationId + "-key", challengeId);
    }

    private String decisionChallenge(String path, String token, String body, String correlationId)
            throws Exception {
        String challengeId = challengeId(call(path, token, correlationId + "-428", body,
                correlationId + "-decision-key", null));
        verify(token, challengeId, correlationId + "-verify");
        return challengeId;
    }

    private void verify(String token, String challengeId, String correlationId) throws Exception {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("OTP captured for " + challengeId).isNotNull();
        HttpResponse<String> verified = call("/api/v1/auth/step-up/verify", token, correlationId,
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null, null);
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
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

    private static String challengeId(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("challenge").get("challengeId").asText();
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

    private static int roleCount(JdbcTemplate jdbc, long userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.user_role ur JOIN banking.role r "
                + "ON r.role_id = ur.role_id WHERE ur.user_id = ? AND r.role_code = 'AUDITOR'", Integer.class,
                userId);
        return count == null ? 0 : count;
    }

    private static int activeLimitCount(JdbcTemplate jdbc) {
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

    /** Each audit row links to the previous chain record; verifies the durable hash chain is intact. */
    private static boolean auditChainIsLinked(JdbcTemplate jdbc) {
        var rows = jdbc.queryForList("SELECT audit_id, prev_hash FROM banking.audit_log ORDER BY audit_id");
        for (int index = 1; index < rows.size(); index++) {
            String previousHash = jdbc.queryForObject(
                    "SELECT record_hash FROM banking.audit_log WHERE audit_id < ? ORDER BY audit_id DESC LIMIT 1",
                    String.class, rows.get(index).get("audit_id"));
            if (!previousHash.equals(rows.get(index).get("prev_hash"))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ fixture helpers

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
                        + "RETURNING customer_id", Long.class, userId, new byte[] {1, 2, 3}));
    }

    private String session(JdbcTemplate jdbc, long userId) {
        String token = "ac028-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }
}
