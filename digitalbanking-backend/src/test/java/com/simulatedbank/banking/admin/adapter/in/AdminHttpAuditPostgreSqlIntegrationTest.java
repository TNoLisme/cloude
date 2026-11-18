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
import java.time.Instant;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * BE-ADM-APP-04 + BE-ADM-API-01: Admin endpoints on the real security chain (session filter, route gates,
 * CSRF, correlation filter, Problem writer, {@code SecurityAccessDeniedAuditor}) with real PostgreSQL 16 rows.
 *
 * <p>Proves the contract items that controller/service tests cannot: anonymous calls are 401 and never audited as
 * 403 denials, authenticated-but-forbidden calls are 403 with exactly one structured
 * {@code SECURITY_ACCESS_DENIED} fact using the catalog entity {@code APPROVAL_REQUEST} (design/01 §6.3) and the
 * caller's correlation id, cookie sessions require a CSRF token, unknown approvals are 404 without an audit fact,
 * and the 428 maker step-up path never creates an approval or C2 audit event.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminHttpAuditPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_http");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    /** Only the OTP transport seam is a double (notification provider absent); challenge state is real. */
    @TestConfiguration
    static class OtpTransport {
        @Bean
        OtpGateway acceptingOtpGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) -> {
                LAST_OTP.set(new String(otp));
                return new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
            };
        }
    }

    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_OTP =
            new java.util.concurrent.atomic.AtomicReference<>();

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @Order(1)
    @DisplayName("API-01: unauthenticated admin writes are 401 and are not audited as 403 denials")
    void anonymousRequestIs401AndNotAudited(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        double before = approvalDeniedEvents();

        HttpResponse<String> response = call("/api/v1/admin/roles", null, "adm-anon", roleBody(jdbc), null);

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "adm-anon");
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .contains("Bearer realm=\"digital-banking\"");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("AUTH_FORBIDDEN");
        // design/01 §5.7: a 401 authentication failure is not a 403 authorization denial.
        assertThat(approvalDeniedEvents()).isEqualTo(before);
        assertThat(output).doesNotContain("\"event\":\"SECURITY_AUDIT\"");
    }

    @Test
    @Order(2)
    @DisplayName("APP-04: forbidden admin write returns 403 with exactly one APPROVAL_REQUEST C3 fact")
    void forbiddenWriteIsDeniedAndAuditedOnce(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long staff = seedStaff(tx, jdbc, "adm-http-ops", "OPERATIONS");
        String token = "adm-http-ops-" + UUID.randomUUID();
        seedSession(tx, jdbc, staff, token, null);
        double before = approvalDeniedEvents();

        HttpResponse<String> response = call("/api/v1/admin/roles", token, "adm-403", roleBody(jdbc), null);

        assertProblem(response, 403, "AUTH_FORBIDDEN", "adm-403");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(token, "ROLE_MANAGE", "ASSIGN_ROLE");
        assertThat(approvalDeniedEvents()).isEqualTo(before + 1);
        assertThat(output).contains(
                "\"event\":\"SECURITY_AUDIT\"",
                "\"action\":\"SECURITY_ACCESS_DENIED\"",
                "\"outcome\":\"REJECTED\"",
                "\"actor_user_id\":" + staff,
                "\"actor_roles\":\"OPERATIONS\"",
                "\"entity_type\":\"APPROVAL_REQUEST\"",
                "\"reason_code\":\"AUTH_FORBIDDEN\"",
                "\"correlation_id\":\"adm-403\"");
        // No raw token, credential, decision note or payload may reach the security log.
        assertThat(output).doesNotContain(token, "Bearer", "synthetic-not-a-real-hash");
    }

    @Test
    @Order(3)
    @DisplayName("APP-04: approve without the CHECKER privilege is 403 and keeps the approval id in the C3 fact")
    void approveWithoutCheckerPrivilegeIsAuditedWithTheApprovalId(CapturedOutput output,
            @Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long admin = seedStaff(tx, jdbc, "adm-http-admin-nochecker", "ADMIN");
        String token = "adm-http-admin-nochecker-" + UUID.randomUUID();
        seedSession(tx, jdbc, admin, token, null);
        double before = approvalDeniedEvents();

        HttpResponse<String> response = call("/api/v1/admin/roles/7001/approve", token, "adm-approve-403",
                "{\"decision\":\"APPROVE\"}", "adm-http-key-nochecker");

        assertProblem(response, 403, "AUTH_FORBIDDEN", "adm-approve-403");
        assertThat(approvalDeniedEvents()).isEqualTo(before + 1);
        assertThat(output).contains(
                "\"entity_type\":\"APPROVAL_REQUEST\"",
                "\"entity_id\":\"7001\"",
                "\"correlation_id\":\"adm-approve-403\"");
        assertThat(output).doesNotContain(token);
    }

    @Test
    @Order(4)
    @DisplayName("API-01: authorized checker on an unknown approval gets 404 without an audit fact or leak")
    void unknownApprovalIs404WithoutAudit(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long checker = seedStaff(tx, jdbc, "adm-http-checker", "ADMIN");
        grantChecker(tx, jdbc, checker);
        String token = "adm-http-checker-" + UUID.randomUUID();
        seedSession(tx, jdbc, checker, token, null);
        double before = approvalDeniedEvents();

        HttpResponse<String> response = call("/api/v1/admin/roles/9000001/approve", token, "adm-approve-404",
                "{\"decision\":\"APPROVE\"}", "adm-http-key-unknown");

        assertProblem(response, 404, "APPROVAL_NOT_FOUND", "adm-approve-404");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        // Anti-enumeration: no state, no id echo beyond the path, no C3 fact for a missing resource.
        assertThat(approvalDeniedEvents()).isEqualTo(before);
        assertThat(response.body()).doesNotContain("admin_idempotency", "APPROVAL_ALREADY_DECIDED", token);
        assertThat(output).doesNotContain("\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @Order(5)
    @DisplayName("API-01: the 428 maker step-up path creates no approval, idempotency row or C2 audit fact")
    void makerWithoutStepUpGets428AndWritesNothing(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long admin = seedStaff(tx, jdbc, "adm-http-maker", "ADMIN");
        String token = "adm-http-maker-" + UUID.randomUUID();
        seedSession(tx, jdbc, admin, token, null);
        long target = seedStaff(tx, jdbc, "adm-http-role-target", "OPERATIONS");

        HttpResponse<String> response = call("/api/v1/admin/roles", token, "adm-428",
                roleBody(target), "adm-http-key-428");

        assertProblem(response, 428, "STEP_UP_REQUIRED", "adm-428");
        JsonNode challenge = JSON.readTree(response.body()).get("challenge");
        assertThat(challenge.get("purpose").asText()).isEqualTo("SENSITIVE_ACTION");
        assertThat(challenge.get("challengeId").asText()).matches("[0-9a-f-]{36}");
        assertThat(response.body()).doesNotContain("otp", "OTP_HASH", "token");
        assertThat(count(jdbc, "SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
        assertThat(output).doesNotContain("\"action\":\"ROLE_CHANGE_REQUESTED\"");
        assertThat(output).doesNotContain("\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @Order(6)
    @DisplayName("APP-04: cookie sessions require the CSRF token; denial is a 403 without a C3 fact")
    void cookieSessionWithoutCsrfTokenIsRejected(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long admin = seedStaff(tx, jdbc, "adm-http-cookie", "ADMIN");
        String token = "adm-http-cookie-" + UUID.randomUUID();
        String csrf = "csrf-" + UUID.randomUUID();
        seedSession(tx, jdbc, admin, token, csrf);
        double before = approvalDeniedEvents();

        HttpResponse<String> withoutToken = cookieCall("/api/v1/admin/roles", token, "adm-csrf-missing",
                roleBody(jdbc), null, null);
        assertProblem(withoutToken, 403, "AUTH_FORBIDDEN", "adm-csrf-missing");
        assertThat(approvalDeniedEvents()).isEqualTo(before);

        HttpResponse<String> withToken = cookieCall("/api/v1/admin/roles", token, "adm-csrf-present",
                roleBody(jdbc), "adm-csrf-key", csrf);
        // CSRF passes, so the request reaches the Maker step-up gate instead of being rejected at the chain.
        assertProblem(withToken, 428, "STEP_UP_REQUIRED", "adm-csrf-present");
        assertThat(output).doesNotContain(csrf, token);
    }

    @Test
    @Order(7)
    @DisplayName("API-01/APP-04: verified step-up creates one PENDING approval with 201, Location and one C2 fact")
    void verifiedStepUpCreatesOneApprovalAndReplaysWithoutDuplicateAudit(CapturedOutput output,
            @Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long maker = seedStaff(tx, jdbc, "adm-http-201-maker", "ADMIN");
        String token = "adm-http-201-maker-" + UUID.randomUUID();
        seedSession(tx, jdbc, maker, token, null);
        long target = seedStaff(tx, jdbc, "adm-http-201-target", "OPERATIONS");
        String body = roleBody(target);

        HttpResponse<String> challengeRequired = call("/api/v1/admin/roles", token, "adm-201-428", body,
                "adm-http-key-201");
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", "adm-201-428");
        String challengeId = JSON.readTree(challengeRequired.body()).get("challenge").get("challengeId").asText();
        String otp = LAST_OTP.get();
        assertThat(otp).matches("[0-9]{6}");

        HttpResponse<String> verified = call("/api/v1/auth/step-up/verify", token, "adm-201-verify",
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null);
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(verified.body()).doesNotContain(otp);

        HttpResponse<String> created = call("/api/v1/admin/roles", token, "adm-201", body, "adm-http-key-201",
                challengeId);

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode summary = JSON.readTree(created.body());
        TreeSet<String> fields = new TreeSet<>();
        summary.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("appliedAt", "approvalId", "checker", "correlationId", "createdAt",
                "decidedAt", "decisionNote", "maker", "requestType", "status", "targetId", "targetType");
        String approvalId = summary.get("approvalId").asText();
        assertThat(approvalId).matches("[1-9][0-9]{0,18}");
        assertThat(summary.get("requestType").asText()).isEqualTo("ROLE_CHANGE");
        assertThat(summary.get("targetType").asText()).isEqualTo("APP_USER");
        assertThat(summary.get("targetId").asText()).isEqualTo(Long.toString(target));
        assertThat(summary.get("status").asText()).isEqualTo("PENDING");
        assertThat(summary.get("maker").get("userId").asText()).isEqualTo(Long.toString(maker));
        assertThat(summary.get("checker").isNull()).isTrue();
        assertThat(summary.get("correlationId").asText()).isEqualTo("adm-201");
        // No persistence entity, provider version or credential may leak through the summary.
        assertThat(created.body()).doesNotContain("expectedVersion", "password", token, "payload");
        assertThat(created.headers().firstValue("Location"))
                .contains("/api/v1/admin/approvals/" + approvalId);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.headers().firstValue("X-Correlation-ID")).contains("adm-201");

        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, Long.parseLong(approvalId))).isEqualTo("PENDING");
        assertThat(output).contains("\"action\":\"ROLE_CHANGE_REQUESTED\"",
                "\"audit_class\":\"C2\"",
                "\"entity_type\":\"APPROVAL_REQUEST\"",
                "\"entity_id\":\"" + approvalId + "\"",
                "\"request_type\":\"ROLE_CHANGE\"",
                "\"outcome\":\"SUCCESS\"",
                "\"correlation_id\":\"adm-201\"");
        assertThat(output).doesNotContain("\"action\":\"SECURITY_ACCESS_DENIED\"", otp, token);

        // Same key + same payload replays the stored 201 body without a second approval or C2 fact.
        HttpResponse<String> replay = call("/api/v1/admin/roles", token, "adm-201-replay", body,
                "adm-http-key-201");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body()).get("approvalId").asText()).isEqualTo(approvalId);
        assertThat(count(jdbc, "SELECT count(*) FROM banking.approval_request WHERE request_type = 'ROLE_CHANGE'"
                + " AND approval_id = " + approvalId)).isEqualTo(1);
        assertThat(output.toString().split("\"action\":\"ROLE_CHANGE_REQUESTED\"", -1)).hasSize(2);
    }

    @Test
    @Order(8)
    @DisplayName("API-01: an unknown role target is 404 USER_NOT_FOUND without state, audit or enumeration")
    void unknownRoleTargetIs404WithoutState(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long admin = seedStaff(tx, jdbc, "adm-http-unknown-target", "ADMIN");
        String token = "adm-http-unknown-target-" + UUID.randomUUID();
        seedSession(tx, jdbc, admin, token, null);
        long approvalsBefore = count(jdbc, "SELECT count(*) FROM banking.approval_request");
        long claimsBefore = count(jdbc, "SELECT count(*) FROM banking.admin_idempotency_record");

        HttpResponse<String> response = call("/api/v1/admin/roles", token, "adm-404-user",
                roleBody(9_000_000_001L), "adm-http-key-unknown-user");

        assertProblem(response, 404, "USER_NOT_FOUND", "adm-404-user");
        assertThat(count(jdbc, "SELECT count(*) FROM banking.approval_request")).isEqualTo(approvalsBefore);
        assertThat(count(jdbc, "SELECT count(*) FROM banking.admin_idempotency_record")).isEqualTo(claimsBefore);
        assertThat(output).doesNotContain("\"action\":\"ROLE_CHANGE_REQUESTED\"",
                "\"action\":\"SECURITY_ACCESS_DENIED\"", token);
    }

    @Test
    @Order(9)
    @DisplayName("SEC-02: free-text secrets never reach responses, logs, audit facts or metric labels")
    void sensitiveFreeTextIsRedactedAndMetricsStayBounded(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long maker = seedStaff(tx, jdbc, "adm-sec-02-maker", "ADMIN");
        String token = "adm-sec-02-" + UUID.randomUUID();
        seedSession(tx, jdbc, maker, token, null);
        long target = seedStaff(tx, jdbc, "adm-sec-02-target", "OPERATIONS");
        String secret = "SYNTHETIC_SEC_02_" + UUID.randomUUID();
        String reason = "password=" + secret + " otp=812345 token=" + secret;
        String body = roleBody(target, reason);

        HttpResponse<String> stepUp = call("/api/v1/admin/roles", token, "adm-sec-02-428", body,
                "adm-sec-02-key");
        assertProblem(stepUp, 428, "STEP_UP_REQUIRED", "adm-sec-02-428");
        assertThat(stepUp.body()).doesNotContain(secret, "812345", "password=");
        String challengeId = JSON.readTree(stepUp.body()).get("challenge").get("challengeId").asText();
        String otp = LAST_OTP.get();
        assertThat(otp).matches("[0-9]{6}");
        assertThat(call("/api/v1/auth/step-up/verify", token, "adm-sec-02-verify",
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}",
                null).statusCode()).isEqualTo(200);

        HttpResponse<String> created = call("/api/v1/admin/roles", token, "adm-sec-02-201", body,
                "adm-sec-02-key", challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.headers().firstValue("Pragma")).contains("no-cache");
        assertThat(created.headers().firstValue("Expires")).contains("0");
        assertThat(created.headers().firstValue("X-Request-ID").orElseThrow()).matches("[0-9a-f-]{36}");
        assertThat(created.body()).doesNotContain(secret, "812345", "password=", "reason");
        String approvalId = JSON.readTree(created.body()).get("approvalId").asText();

        HttpResponse<String> detail = get("/api/v1/admin/approvals/" + approvalId, token, "adm-sec-02-detail");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(detail.headers().firstValue("Pragma")).contains("no-cache");
        assertThat(detail.headers().firstValue("Expires")).contains("0");
        assertThat(detail.headers().firstValue("X-Request-ID").orElseThrow()).matches("[0-9a-f-]{36}");
        assertThat(detail.body()).doesNotContain(secret, "812345")
                .contains("password=[REDACTED]", "otp=[REDACTED]", "token=[REDACTED]");

        // The structured security log carries only the catalog fact: no reason/payload/decision note.
        assertThat(output).doesNotContain(secret, "812345", reason);
        assertThat(output).contains("\"action\":\"ROLE_CHANGE_REQUESTED\"", "\"audit_class\":\"C2\"");
        assertThat(output).doesNotContain("\"decisionNote\"", "\"payload\":");

        // Metric labels are a bounded catalog, never actor/approval/correlation or client text.
        for (var meter : meterRegistry.find("admin.audit.events").meters()) {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("action", "class", "outcome");
                assertThat(tag.getValue()).doesNotContain(secret, token, "adm-sec-02", "sha256:")
                        .isNotEqualTo(approvalId);
            });
        }
    }

    // ------------------------------------------------------------------ helpers
    private double approvalDeniedEvents() {
        return meterRegistry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "APPROVAL_REQUEST").count();
    }

    private HttpResponse<String> call(String path, String bearerToken, String correlationId, String body,
            String idempotencyKey) throws Exception {
        return call(path, bearerToken, correlationId, body, idempotencyKey, null);
    }

    private HttpResponse<String> call(String path, String bearerToken, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
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

    private HttpResponse<String> get(String path, String bearerToken, String correlationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> cookieCall(String path, String sessionToken, String correlationId, String body,
            String idempotencyKey, String csrfToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Cookie", "DB_SESSION=" + sessionToken);
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (csrfToken != null) {
            builder.header("X-CSRF-Token", csrfToken);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private String roleBody(JdbcTemplate jdbc) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return roleBody(seedStaff(tx(jdbc), jdbc, "adm-http-body-target-" + unique, "OPERATIONS"));
    }

    private static String roleBody(long targetUserId) {
        return roleBody(targetUserId, "synthetic review reason");
    }

    private static String roleBody(long targetUserId, String reason) {
        try {
            return "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + targetUserId
                    + "\",\"roleCode\":\"AUDITOR\",\"reason\":" + JSON.writeValueAsString(reason) + "}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private TransactionTemplate tx(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long seedStaff(TransactionTemplate tx, JdbcTemplate jdbc, String username, String roleCode) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            return userId;
        });
    }

    private static void grantChecker(TransactionTemplate tx, JdbcTemplate jdbc, long userId) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", userId));
    }

    private static void seedSession(TransactionTemplate tx, JdbcTemplate jdbc, long userId, String token,
            String rawCsrfToken) {
        String csrfHash = rawCsrfToken == null ? "c".repeat(64) : HmacService.sha256Hex(rawCsrfToken);
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), csrfHash, userId));
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        TreeSet<String> fields = new TreeSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("type", "title", "status", "detail", "instance", "code", "correlationId",
                "timestamp", "retryable");
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("instance").asText()).startsWith("/api/");
        assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
        // RFC 9457 body must never expose stack traces, SQL, or internal package names.
        assertThat(body.get("detail").asText())
                .isNotBlank()
                .doesNotContain("Exception", "org.springframework", "PSQLException", "banking.");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        // BE-ADM-SEC-02: every sensitive error response is non-cacheable and carries the server request id.
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Pragma")).contains("no-cache");
        assertThat(response.headers().firstValue("Expires")).contains("0");
        assertThat(response.headers().firstValue("X-Request-ID").orElseThrow()).matches("[0-9a-f-]{36}");
    }
}
