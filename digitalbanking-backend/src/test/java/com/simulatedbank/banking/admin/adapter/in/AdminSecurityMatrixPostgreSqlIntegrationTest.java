package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
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
 * BE-ADM-SEC-01: actor/role/data-scope authorization matrix of the Admin surface on the real security
 * chain (session filter, URL gates, CSRF, correlation, Problem writer, C3 auditor), real Identity
 * session/authority resolution and real PostgreSQL rows.
 *
 * <p>Covered at the HTTP boundary because the gate is part of the contract: CUSTOMER actors are denied
 * every Admin route with 403 and a C3 fact; forged role/user claims (headers or body) never escalate;
 * disabled, expired and revoked sessions are 401; AUDITOR keeps only its approved permissions; a maker
 * can neither propose a role for themselves nor approve their own request; a checker without the
 * approval-type permission is denied. Service-layer re-checks (invoked without HTTP) are covered by
 * {@code AdminAuthorizationTest}, {@code ApprovalProposalServiceTest} and {@code ApprovalDecisionServiceTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class AdminSecurityMatrixPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSRF_HASH = "c".repeat(64);
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_security");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    /** Only the OTP transport is a double (notification provider absent); challenge state stays real. */
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

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
    }

    // ------------------------------------------------------------------ CUSTOMER isolation

    @Test
    @DisplayName("SEC-01: a CUSTOMER actor is denied every Admin route with 403 and a C3 fact")
    void customerActorIsDeniedEveryAdminRoute(CapturedOutput output) throws Exception {
        Principal customer = customer();
        String target = Long.toString(customer.customerId());

        double deniedBefore = deniedEvents("CUSTOMER");
        HttpResponse<String> customerDetail = get("/api/v1/admin/customers/" + target, customer.token(),
                "sec-cus-detail");
        assertProblem(customerDetail, 403, "AUTH_FORBIDDEN", "sec-cus-detail");
        assertThat(deniedEvents("CUSTOMER")).isEqualTo(deniedBefore + 1);

        assertProblem(get("/api/v1/admin/customers/" + target + "/accounts", customer.token(), "sec-cus-acc"),
                403, "AUTH_FORBIDDEN", "sec-cus-acc");
        assertProblem(get("/api/v1/admin/approvals", customer.token(), "sec-cus-list"), 403,
                "AUTH_FORBIDDEN", "sec-cus-list");
        assertProblem(get("/api/v1/admin/approvals/1", customer.token(), "sec-cus-one"), 403,
                "AUTH_FORBIDDEN", "sec-cus-one");
        assertProblem(post("/api/v1/admin/roles", customer.token(), "sec-cus-role", roleBody(1), null, null),
                403, "AUTH_FORBIDDEN", "sec-cus-role");
        assertProblem(post("/api/v1/admin/roles/1/approve", customer.token(), "sec-cus-approve",
                "{\"decision\":\"APPROVE\"}", "sec-cus-key", null), 403, "AUTH_FORBIDDEN", "sec-cus-approve");
        assertProblem(post("/api/v1/admin/limits", customer.token(), "sec-cus-limit", "{}", "sec-cus-limit-key",
                null), 403, "AUTH_FORBIDDEN", "sec-cus-limit");

        // No Admin denial may leak customer data, permissions, tokens or internal types.
        assertThat(customerDetail.body()).doesNotContain("fullName", "phoneMasked", "CUSTOMER_READ",
                customer.token(), "banking.", "Exception");
        assertThat(output).doesNotContain(customer.token());
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"", "\"entity_type\":\"CUSTOMER\"");
    }

    @Test
    @DisplayName("SEC-01: forged role/user headers and body claims never escalate an actor")
    void forgedActorClaimsDoNotEscalate(CapturedOutput output) throws Exception {
        Principal operations = staff("sec-forged-ops", "OPERATIONS", false);
        long adminId = staff("sec-forged-admin", "ADMIN", false).userId();
        long target = staff("sec-forged-target", "OPERATIONS", false).userId();

        // Header claims are not part of the trusted context: OPERATIONS still lacks ROLE_MANAGE.
        HttpResponse<String> denied = postWithHeaders("/api/v1/admin/roles", operations.token(),
                "sec-forged-403", roleBody(target),
                Map.of("X-User-Id", Long.toString(adminId), "X-Role", "ADMIN",
                        "X-Actor-User-Id", Long.toString(adminId), "X-Permissions", "ROLE_MANAGE"));
        assertProblem(denied, 403, "AUTH_FORBIDDEN", "sec-forged-403");
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.auth_challenge")).isZero();

        // A body-level actor field is not part of the approved schema: fail closed as malformed, no state.
        Principal admin = staff("sec-forged-maker", "ADMIN", false);
        String forgedBody = "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                + "\",\"roleCode\":\"AUDITOR\",\"makerId\":\"" + adminId + "\"}";
        assertProblem(post("/api/v1/admin/roles", admin.token(), "sec-forged-body", forgedBody,
                "sec-forged-body-key", null), 400, "MALFORMED_REQUEST", "sec-forged-body");
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();

        // The authorized maker still gets a challenge bound to the authenticated principal, not to any
        // client supplied id.
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles", admin.token(), "sec-forged-428",
                roleBody(target), "sec-forged-key", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", "sec-forged-428");
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", admin.userId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", adminId)).isZero();
        assertThat(output).doesNotContain(operations.token());
    }

    // ------------------------------------------------------------------ session validity

    @Test
    @DisplayName("SEC-01: disabled, expired and revoked sessions are rejected with 401")
    void disabledExpiredAndRevokedSessionsAreRejected() throws Exception {
        Principal disabled = staff("sec-session-disabled", "ADMIN", false);
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = ?", disabled.userId()));
        assertProblem(get("/api/v1/admin/approvals", disabled.token(), "sec-disabled"), 401,
                "AUTH_SESSION_EXPIRED", "sec-disabled");

        Principal expired = staff("sec-session-expired", "ADMIN", false);
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET last_activity_at = now() - interval '2 minutes', "
                        + "idle_expires_at = now() - interval '1 minute' WHERE session_token_hash = ?",
                HmacService.sha256Hex(expired.token())));
        assertProblem(get("/api/v1/admin/approvals", expired.token(), "sec-expired"), 401,
                "AUTH_SESSION_EXPIRED", "sec-expired");

        Principal revoked = staff("sec-session-revoked", "OPERATIONS", false);
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET status = 'LOGGED_OUT', logged_out_at = now() "
                        + "WHERE session_token_hash = ?", HmacService.sha256Hex(revoked.token())));
        assertProblem(get("/api/v1/admin/customers/1", revoked.token(), "sec-revoked"), 401,
                "AUTH_SESSION_EXPIRED", "sec-revoked");
    }

    @Test
    @DisplayName("SEC-01: missing or unverifiable credentials are 401, never 403 or 200")
    void missingOrUnverifiableCredentialsAre401() throws Exception {
        assertProblem(get("/api/v1/admin/approvals", null, "sec-anon"), 401, "AUTH_SESSION_EXPIRED", "sec-anon");
        assertProblem(get("/api/v1/admin/customers/1", null, "sec-anon-cus"), 401, "AUTH_SESSION_EXPIRED",
                "sec-anon-cus");
        assertProblem(get("/api/v1/admin/approvals", "not-a-real-token", "sec-bogus"), 401,
                "AUTH_SESSION_EXPIRED", "sec-bogus");
        assertProblem(get("/api/v1/admin/approvals", "bad token with spaces", "sec-bad-token"), 401,
                "AUTH_SESSION_EXPIRED", "sec-bad-token");
    }

    // ------------------------------------------------------------------ role matrix

    @Test
    @DisplayName("SEC-01: AUDITOR keeps only its approved read permissions")
    void auditorKeepsOnlyApprovedPermissions() throws Exception {
        Principal target = customer();
        Principal auditor = staff("sec-auditor", "AUDITOR", false);

        // AUDITOR holds ACCOUNT_READ (07 §3.4) but not CUSTOMER_READ and not the approval roles.
        HttpResponse<String> accounts = get(
                "/api/v1/admin/customers/" + target.customerId() + "/accounts", auditor.token(), "sec-aud-acc");
        assertThat(accounts.statusCode()).isEqualTo(200);
        assertProblem(get("/api/v1/admin/customers/" + target.customerId(), auditor.token(), "sec-aud-cus"),
                403, "AUTH_FORBIDDEN", "sec-aud-cus");
        assertProblem(get("/api/v1/admin/approvals", auditor.token(), "sec-aud-appr"), 403,
                "AUTH_FORBIDDEN", "sec-aud-appr");
        assertProblem(post("/api/v1/admin/roles", auditor.token(), "sec-aud-write", roleBody(1), "sec-aud-key",
                null), 403, "AUTH_FORBIDDEN", "sec-aud-write");
    }

    @Test
    @DisplayName("SEC-01: a checker without ROLE_MANAGE cannot approve a role change and changes nothing")
    void checkerWithoutRoleManageIsDenied() throws Exception {
        Principal operationsChecker = staff("sec-ops-checker", "OPERATIONS", true);

        assertProblem(post("/api/v1/admin/roles/123/approve", operationsChecker.token(), "sec-checker-403",
                "{\"decision\":\"APPROVE\"}", "sec-checker-key", null), 403, "AUTH_FORBIDDEN",
                "sec-checker-403");
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
    }

    // ------------------------------------------------------------------ segregation of duties

    @Test
    @DisplayName("SEC-01: a maker cannot propose a role change for themselves")
    void makerCannotProposeRoleForSelf() throws Exception {
        Principal maker = staff("sec-self-maker", "ADMIN", true);

        assertProblem(post("/api/v1/admin/roles", maker.token(), "sec-self-propose",
                roleBody(maker.userId()), "sec-self-key", null), 409, "ROLE_CONFLICT", "sec-self-propose");

        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", maker.userId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM banking.audit_log WHERE action = 'SECURITY_ACCESS_DENIED' "
                + "AND entity_type = 'APP_USER' AND actor_user_id = ? AND correlation_id = ?",
                maker.userId(), "sec-self-propose")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-01: a maker cannot approve their own request even with the CHECKER privilege")
    void makerCannotApproveOwnRequest(CapturedOutput output) throws Exception {
        Principal maker = staff("sec-self-approve", "ADMIN", true);
        long target = staff("sec-self-approve-target", "OPERATIONS", false).userId();
        long approvalId = proposeRole(maker, target, "sec-self-create");

        assertProblem(post("/api/v1/admin/roles/" + approvalId + "/approve", maker.token(),
                "sec-self-approve", "{\"decision\":\"APPROVE\"}", "sec-self-approve-key", null),
                409, "ROLE_CONFLICT", "sec-self-approve");

        assertThat(approvalStatus(approvalId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM banking.user_role ur JOIN banking.role r "
                + "ON r.role_id = ur.role_id WHERE ur.user_id = ? AND r.role_code = 'AUDITOR'", target))
                .isZero();
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"",
                "\"entity_type\":\"APPROVAL_REQUEST\"");
    }

    // ------------------------------------------------------------------ helpers

    private record Principal(long userId, long customerId, String token) {
    }

    private long proposeRole(Principal maker, long target, String correlation) throws Exception {
        String body = roleBody(target);
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles", maker.token(),
                correlation + "-428", body, correlation + "-key", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlation + "-428");
        String challengeId = JSON.readTree(challengeRequired.body()).get("challenge").get("challengeId").asText();
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("OTP captured for the maker challenge").isNotNull();
        HttpResponse<String> verified = post("/api/v1/auth/step-up/verify", maker.token(),
                correlation + "-verify",
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}",
                null, null);
        assertThat(verified.statusCode()).isEqualTo(200);

        HttpResponse<String> created = post("/api/v1/admin/roles", maker.token(), correlation, body,
                correlation + "-key", challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private static String roleBody(long targetUserId) {
        return "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + targetUserId
                + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"synthetic security matrix\"}";
    }

    private Principal customer() {
        String suffix = unique();
        long userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'CUSTOMER', 'ACTIVE')", "sec-cus-" + suffix);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, "sec-cus-" + suffix);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", id);
            return id;
        });
        long customerId = tx.execute(ignored -> jdbc.queryForObject(
                "INSERT INTO banking.customer (user_id, full_name_enc, status) VALUES (?, ?, 'ACTIVE') "
                        + "RETURNING customer_id",
                Long.class, userId, new byte[] {1, 2, 3}));
        return new Principal(userId, customerId, session(userId));
    }

    private Principal staff(String prefix, String roleCode, boolean checkerPrivilege) {
        String suffix = unique();
        String username = prefix + "-" + suffix;
        long userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, roleCode);
            if (checkerPrivilege) {
                jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", id);
            }
            return id;
        });
        return new Principal(userId, 0L, session(userId));
    }

    private String session(long userId) {
        String token = "sec-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), CSRF_HASH, userId));
        return token;
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-Correlation-ID", correlationId);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        return sendPost(path, token, correlationId, body, idempotencyKey, stepUpChallenge, Map.of());
    }

    private HttpResponse<String> postWithHeaders(String path, String token, String correlationId, String body,
            Map<String, String> headers) throws Exception {
        return sendPost(path, token, correlationId, body, null, null, headers);
    }

    private HttpResponse<String> sendPost(String path, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .header("X-Correlation-ID", correlationId);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (stepUpChallenge != null) {
            builder.header("X-Step-Up-Challenge", stepUpChallenge);
        }
        headers.forEach(builder::header);
        return http().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private double deniedEvents(String entityType) {
        return meterRegistry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", entityType).count();
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private String approvalStatus(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("instance").asText()).startsWith("/api/");
        assertThat(body.get("detail").asText())
                .isNotBlank()
                .doesNotContain("Exception", "org.springframework", "PSQLException", "banking.");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
    }
}
