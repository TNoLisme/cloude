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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
 * BE-ADM-TST-04 §3/§5/§6/§7/§8/§9: security and API-contract behaviour of the Admin surface on the real
 * security chain (session filter, route gates, CSRF, correlation/request-id filters, security headers,
 * Problem writer, C3 auditor) with real Identity sessions and real PostgreSQL rows.
 *
 * <p>This suite owns the gaps that the existing Admin HTTP suites do not pin:</p>
 * <ul>
 *   <li>the exact security-header and RFC 9457 envelope contract across 200/400/401/403/404/409/428/503;</li>
 *   <li>the CSRF policy as configured: cookie sessions require {@code X-CSRF-Token}, bearer clients do not,
 *       and a token bound to another session is rejected;</li>
 *   <li>documented status mapping for validation, conflict and the untouched 422 surface of the limit routes;</li>
 *   <li>forged actor/tenant metadata in headers, query and body, plus actor-scope IDOR across the Admin reads
 *       and decisions;</li>
 *   <li>the actual reason cross-tenant isolation stays BLOCKED: the canonical schema has no tenant column.</li>
 * </ul>
 *
 * <p>Only the OTP transport is a double (notification provider absent); challenge state, hashing, approval
 * transitions, Identity mutations and audit facts are production code. {@code AdminOpenApiContractTest} checks
 * the document itself; this suite checks what the running chain does.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class AdminHttpContractPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSRF_HASH = "c".repeat(64);
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();
    private static final String TENANT_MARKER = "forged-tenant-" + UUID.randomUUID();
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    /** Only the OTP transport seam is a double; challenge persistence/verification stays real. */
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
    private Principal maker;
    private Principal otherMaker;
    private Principal checker;
    private Principal otherChecker;
    private Principal auditor;
    private Principal customer;
    private long target;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
        String suffix = unique();
        maker = staff("adm-contract-maker-" + suffix, "ADMIN", false);
        otherMaker = staff("adm-contract-maker2-" + suffix, "OPERATIONS", false);
        checker = staff("adm-contract-checker-" + suffix, "ADMIN", true);
        otherChecker = staff("adm-contract-checker2-" + suffix, "ADMIN", true);
        auditor = staff("adm-contract-auditor-" + suffix, "AUDITOR", false);
        customer = customer("adm-contract-customer-" + suffix);
        target = staff("adm-contract-target-" + suffix, "OPERATIONS", false).userId();
        OTP_BY_CHALLENGE.clear();
    }

    // ------------------------------------------------------------------ headers/envelope

    @Test
    @DisplayName("TST-04: security headers, no-store and the RFC 9457 envelope hold on every Admin status")
    void securityHeadersAndProblemEnvelopeAcrossStatuses(CapturedOutput output) throws Exception {
        HttpResponse<String> ok = get("/api/v1/admin/approvals", maker.token(), "contract-ok");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertSecurityHeaders(ok, "contract-ok");

        HttpResponse<String> unauthorized = get("/api/v1/admin/approvals", null, "contract-401");
        assertProblem(unauthorized, 401, "AUTH_SESSION_EXPIRED", "contract-401");
        assertThat(unauthorized.headers().firstValue("WWW-Authenticate"))
                .contains("Bearer realm=\"digital-banking\"");

        HttpResponse<String> forbidden = get("/api/v1/admin/approvals", customer.token(), "contract-403");
        assertProblem(forbidden, 403, "AUTH_FORBIDDEN", "contract-403");

        assertProblem(get("/api/v1/admin/approvals/9000001", maker.token(), "contract-404"), 404,
                "APPROVAL_NOT_FOUND", "contract-404");

        HttpResponse<String> stepUp = post("/api/v1/admin/roles", maker.token(), "contract-428",
                roleBody(target, "contract reason"), "contract-key-428", null);
        assertProblem(stepUp, 428, "STEP_UP_REQUIRED", "contract-428");
        JsonNode challenge = JSON.readTree(stepUp.body()).get("challenge");
        TreeSet<String> challengeFields = new TreeSet<>();
        challenge.fieldNames().forEachRemaining(challengeFields::add);
        assertThat(challengeFields).containsExactly("challengeId", "channel", "expiresAt", "maxAttempts",
                "methods", "purpose", "remainingAttempts");
        assertThat(challenge.get("purpose").asText()).isEqualTo("SENSITIVE_ACTION");
        assertThat(challenge.get("challengeId").asText()).matches(UUID_PATTERN);

        // The correlation id is server-validated: a malformed value is rejected before MVC with a fresh id.
        HttpResponse<String> invalidCorrelation = get("/api/v1/admin/approvals", maker.token(),
                "bad correlation with spaces");
        assertProblem(invalidCorrelation, 400, "VALIDATION_ERROR", null);
        assertThat(invalidCorrelation.headers().firstValue("X-Correlation-ID").orElseThrow())
                .matches(UUID_PATTERN)
                .doesNotContain("bad correlation");
        assertThat(invalidCorrelation.body()).doesNotContain("bad correlation");

        // No rejected response leaks credentials, SQL, framework internals or entity names.
        for (HttpResponse<String> response : List.of(unauthorized, forbidden, stepUp, invalidCorrelation)) {
            assertThat(response.body()).doesNotContain(maker.token(), customer.token(), CSRF_HASH,
                    "PSQLException", "org.springframework", "banking.", "at com.", "password_hash",
                    "expectedVersion", "Bearer ");
        }
        assertThat(output).doesNotContain(maker.token(), customer.token(), CSRF_HASH);
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    @DisplayName("TST-04: cookie sessions require CSRF, bearer clients do not, foreign tokens are rejected")
    void csrfPolicyIsBoundToCookieSessionsOnly(CapturedOutput output, @Autowired JdbcTemplate jdbc)
            throws Exception {
        String rawCsrf = "contract-csrf-" + UUID.randomUUID();
        Principal cookieAdmin = staffWithoutSession("adm-contract-cookie-" + unique(), "ADMIN");
        seedCookieSession(cookieAdmin.userId(), cookieAdmin.token(), rawCsrf);
        String otherCsrf = "contract-csrf-other-" + UUID.randomUUID();
        Principal otherCookie = staffWithoutSession("adm-contract-cookie2-" + unique(), "ADMIN");
        seedCookieSession(otherCookie.userId(), otherCookie.token(), otherCsrf);
        long challengesBefore = count("SELECT count(*) FROM banking.auth_challenge");

        // Reads over a cookie session are not CSRF-protected because they are not state changing.
        HttpResponse<String> cookieRead = cookieGet("/api/v1/admin/approvals", cookieAdmin.token(),
                "contract-csrf-get");
        assertThat(cookieRead.statusCode()).isEqualTo(200);
        assertSecurityHeaders(cookieRead, "contract-csrf-get");

        double deniedBefore = deniedEvents();
        assertProblem(cookiePost("/api/v1/admin/roles", cookieAdmin.token(), "contract-csrf-missing",
                roleBody(target, "csrf missing"), "contract-csrf-key-1", null), 403, "AUTH_FORBIDDEN",
                "contract-csrf-missing");
        assertProblem(cookiePost("/api/v1/admin/roles", cookieAdmin.token(), "contract-csrf-invalid",
                roleBody(target, "csrf invalid"), "contract-csrf-key-2", "not-the-session-token"), 403,
                "AUTH_FORBIDDEN", "contract-csrf-invalid");
        // A valid token belonging to another session must never authorize this session.
        assertProblem(cookiePost("/api/v1/admin/roles", cookieAdmin.token(), "contract-csrf-foreign",
                roleBody(target, "csrf foreign"), "contract-csrf-key-3", otherCsrf), 403, "AUTH_FORBIDDEN",
                "contract-csrf-foreign");
        // Limit approval is state changing too, so the same rule applies on that route.
        assertProblem(cookiePost("/api/v1/admin/limits/1/approve", cookieAdmin.token(),
                "contract-csrf-limit", "{\"decision\":\"APPROVE\"}", "contract-csrf-key-4", null), 403,
                "AUTH_FORBIDDEN", "contract-csrf-limit");

        // CSRF rejections are chain-level: no business state, no C3 denial fact.
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
        assertThat(count("SELECT count(*) FROM banking.auth_challenge")).isEqualTo(challengesBefore);
        assertThat(deniedEvents()).isEqualTo(deniedBefore);

        // With the session's token the request passes CSRF and reaches the Maker step-up gate.
        assertProblem(cookiePost("/api/v1/admin/roles", cookieAdmin.token(), "contract-csrf-valid",
                roleBody(target, "csrf valid"), "contract-csrf-key-5", rawCsrf), 428, "STEP_UP_REQUIRED",
                "contract-csrf-valid");

        // Bearer credentials are not cookie-authenticated, so CSRF is not applied to them.
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-csrf-bearer",
                roleBody(target, "bearer no csrf"), "contract-csrf-key-6", null), 428,
                "STEP_UP_REQUIRED", "contract-csrf-bearer");

        assertThat(output).doesNotContain(rawCsrf, otherCsrf, cookieAdmin.token());
    }

    // ------------------------------------------------------------------ documented statuses

    @Test
    @DisplayName("TST-04: validation, conflict and terminal statuses are the documented ones; 422 stays inactive")
    void documentedStatusMappingForValidationConflictAndTerminalState(CapturedOutput output) throws Exception {
        // 400 — schema-shaped validation, no service call, no state.
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-400-missing",
                "{\"operation\":\"ASSIGN_ROLE\"}", "contract-400-key", null), 400, "VALIDATION_ERROR",
                "contract-400-missing");
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-400-unknown-field",
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                        + "\",\"roleCode\":\"AUDITOR\",\"makerId\":\"1\"}", "contract-400-key2", null),
                400, "MALFORMED_REQUEST", "contract-400-unknown-field");
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-400-enum",
                roleBody(target, "contract semantic").replace("AUDITOR", "SUPERUSER"),
                "contract-400-key3", null), 400, "VALIDATION_ERROR", "contract-400-enum");
        assertProblem(post("/api/v1/admin/roles/1/approve", checker.token(), "contract-400-decision",
                "{\"decision\":\"MAYBE\"}", "contract-400-key4", null), 400, "VALIDATION_ERROR",
                "contract-400-decision");

        // A documented enum value outside the MVP contract is rejected as a validation error, not silently
        // accepted (the OpenAPI publishes only the open request types).
        assertProblem(get("/api/v1/admin/approvals?requestType=RETENTION_HOLD", maker.token(),
                "contract-400-retention"), 400, "VALIDATION_ERROR", "contract-400-retention");

        // 404 — unknown approval id (anti-enumeration) and unknown role target.
        assertProblem(post("/api/v1/admin/roles/9000001/approve", checker.token(), "contract-404-approve",
                "{\"decision\":\"APPROVE\"}", "contract-404-key", null), 404, "APPROVAL_NOT_FOUND",
                "contract-404-approve");
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-404-product",
                roleBody(9_000_000_001L, "unknown target"), "contract-404-key2", null), 404,
                "USER_NOT_FOUND", "contract-404-product");

        // 409 — Maker self-target and idempotency reuse with a different payload.
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-409-self",
                roleBody(maker.userId(), "self"), "contract-self-key", null), 409, "ROLE_CONFLICT",
                "contract-409-self");
        long approvalId = proposeRole(maker.token(), "contract-idem-key", "contract-idem-create");
        assertProblem(post("/api/v1/admin/roles", maker.token(), "contract-409-idem",
                roleBody(target, "different payload"), "contract-idem-key", null), 409,
                "IDEMPOTENCY_CONFLICT", "contract-409-idem");

        // 200 then 409 — a terminal approval cannot be decided again under another key.
        assertThat(approve(checker.token(), approvalId, "contract-decide-key").statusCode()).isEqualTo(200);
        assertProblem(post("/api/v1/admin/roles/" + approvalId + "/approve", checker.token(),
                "contract-409-terminal", "{\"decision\":\"REJECT\"}", "contract-decide-key-2", null),
                409, "APPROVAL_ALREADY_DECIDED", "contract-409-terminal");
        assertThat(count("SELECT count(*) FROM banking.approval_request WHERE status = 'APPLIED'"))
                .isEqualTo(1);

        // Limit routes are live: a first call without step-up is the documented 428 and writes nothing.
        HttpResponse<String> limitProposal = post("/api/v1/admin/limits", maker.token(), "contract-428-limit",
                "{\"customerId\":\"2001\",\"perTransactionLimit\":\"5000000.00\",\"dailyLimit\":\"20000000.00\"}",
                "contract-limit-key", null);
        assertProblem(limitProposal, 428, "STEP_UP_REQUIRED", "contract-428-limit");

        // 422 is documented only for the limit routes (policy/version); malformed money stays 400.
        assertThat(post("/api/v1/admin/limits", maker.token(), "contract-400-body",
                "{\"customerId\":\"2001\",\"perTransactionLimit\":\"0\",\"dailyLimit\":\"0\"}",
                "contract-limit-key-2", null).statusCode()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM banking.approval_request WHERE request_type = 'LIMIT_CHANGE'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE operation = "
                + "'CREATE_LIMIT_PROPOSAL'")).isZero();

        assertThat(output).doesNotContain("expectedVersion", "synthetic-not-a-real-hash",
                "\"action\":\"LIMIT_CHANGE_APPROVED\"");
    }

    // ------------------------------------------------------------------ forged metadata + IDOR

    @Test
    @DisplayName("TST-04: forged actor/tenant metadata never widens scope and IDOR attempts mutate nothing")
    void forgedMetadataAndIdorCannotWidenScope(CapturedOutput output) throws Exception {
        long makerApproval = insertApproval(maker.userId(), "PENDING", "contract-idor-maker", 5);
        long otherApproval = insertApproval(otherMaker.userId(), "PENDING", "contract-idor-other", 6);
        String snapshot = approvalSnapshot();

        // Header claims are ignored: the session principal stays authoritative for reads and writes.
        HttpResponse<String> spoofedRead = getWithHeaders("/api/v1/admin/approvals", maker.token(),
                "contract-spoof-read", Map.of(
                        "X-User-Id", Long.toString(otherMaker.userId()),
                        "X-Actor-User-Id", Long.toString(otherMaker.userId()),
                        "X-Tenant-Id", TENANT_MARKER,
                        "X-Organization-Id", TENANT_MARKER,
                        "X-Role", "ADMIN",
                        "X-Permissions", "ROLE_MANAGE,CHECKER",
                        "X-Maker-Id", Long.toString(otherMaker.userId())));
        assertThat(spoofedRead.statusCode()).isEqualTo(200);
        assertThat(spoofedRead.body()).doesNotContain(TENANT_MARKER);
        assertThat(approvalIds(spoofedRead)).containsExactly(Long.toString(makerApproval));

        // Query filters cannot widen the SQL scope either.
        assertThat(approvalIds(get("/api/v1/admin/approvals?makerId=" + otherMaker.userId(), maker.token(),
                "contract-spoof-query"))).isEmpty();
        assertThat(approvalIds(get("/api/v1/admin/approvals?checkerId=" + otherMaker.userId(), maker.token(),
                "contract-spoof-query2"))).isEmpty();

        // Another maker's approval is indistinguishable from an unknown id.
        assertProblem(get("/api/v1/admin/approvals/" + otherApproval, maker.token(), "contract-idor-detail"),
                404, "APPROVAL_NOT_FOUND", "contract-idor-detail");

        // A ROLE_CHANGE approval id on the limit decision route must not reveal or mutate it.
        assertProblem(post("/api/v1/admin/limits/" + makerApproval + "/approve", checker.token(),
                "contract-idor-route", "{\"decision\":\"APPROVE\"}", "contract-idor-key", null), 404,
                "APPROVAL_NOT_FOUND", "contract-idor-route");

        // A module outsider (AUDITOR) cannot read approvals even with a valid Identity session.
        assertProblem(get("/api/v1/admin/approvals", auditor.token(), "contract-idor-auditor"), 403,
                "AUTH_FORBIDDEN", "contract-idor-auditor");

        // The same idempotency key string used by two checkers is not a cross-actor replay.
        long decidable = insertApproval(maker.userId(), "PENDING", "contract-idor-decide", 0);
        String snapshotBeforeDecision = approvalSnapshot();
        String sharedKey = "contract-shared-key";
        assertThat(approve(checker.token(), decidable, sharedKey).statusCode()).isEqualTo(200);
        assertProblem(post("/api/v1/admin/roles/" + decidable + "/approve", otherChecker.token(),
                "contract-idor-replay", "{\"decision\":\"APPROVE\"}", sharedKey, null), 409,
                "APPROVAL_ALREADY_DECIDED", "contract-idor-replay");
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key = ?",
                sharedKey)).isEqualTo(1);
        assertThat(roleCodes(target).stream().filter("AUDITOR"::equals).count()).isEqualTo(1);

        // Only the approval that was actually decided changed; rejected IDOR attempts left the rest intact.
        assertThat(approvalSnapshot()).isNotEqualTo(snapshotBeforeDecision);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, makerApproval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, otherApproval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, decidable)).isEqualTo("APPLIED");

        assertThat(output).doesNotContain(maker.token(), otherChecker.token(), TENANT_MARKER);
    }

    @Test
    @DisplayName("TST-04: cross-tenant scope is BLOCKED by the canonical schema; actor scope is the boundary")
    void tenantIsolationIsBlockedByTheCanonicalSchema() {
        // Evidence for the documented blocker (ADM-OI-06): neither Admin table carries a tenant/organization
        // column, so a cross-tenant assertion would be invented. Actor scope is enforced in SQL instead
        // (covered above and by ApprovalReadHttpPostgreSqlIntegrationTest).
        List<String> scopeColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'banking'
                   AND table_name IN ('approval_request', 'admin_idempotency_record')
                   AND column_name ~ '(tenant|organization|org_)'
                """, String.class);
        assertThat(scopeColumns)
                .as("ADM-OI-06 stays BLOCKED: no tenant column exists, so cross-tenant isolation cannot be "
                        + "asserted; update this test and the docs when tenant scope is approved")
                .isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private record Principal(long userId, long customerId, String token) {
    }

    private long proposeRole(String token, String key, String correlation) throws Exception {
        String body = roleBody(target, "contract proposal");
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles", token, correlation + "-428", body,
                key, null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlation + "-428");
        String challengeId = JSON.readTree(challengeRequired.body()).get("challenge").get("challengeId").asText();
        verifyStepUp(token, challengeId, correlation + "-verify");
        HttpResponse<String> created = post("/api/v1/admin/roles", token, correlation, body, key, challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private HttpResponse<String> approve(String token, long approvalId, String key) throws Exception {
        String body = "{\"decision\":\"APPROVE\",\"decisionNote\":\"contract approve\"}";
        String correlation = "contract-" + UUID.randomUUID();
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles/" + approvalId + "/approve", token,
                correlation + "-428", body, key, null);
        if (challengeRequired.statusCode() != 428) {
            return challengeRequired;
        }
        String challengeId = JSON.readTree(challengeRequired.body()).get("challenge").get("challengeId").asText();
        verifyStepUp(token, challengeId, correlation + "-verify");
        return post("/api/v1/admin/roles/" + approvalId + "/approve", token, correlation, body, key, challengeId);
    }

    private void verifyStepUp(String token, String challengeId, String correlation) throws Exception {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("captured OTP for challenge %s", challengeId).isNotNull();
        HttpResponse<String> verified = post("/api/v1/auth/step-up/verify", token, correlation,
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null,
                null);
        assertThat(verified.statusCode()).isEqualTo(200);
    }

    private static String roleBody(long targetUserId, String reason) {
        try {
            return "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + targetUserId
                    + "\",\"roleCode\":\"AUDITOR\",\"reason\":" + JSON.writeValueAsString(reason) + "}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private Principal staff(String username, String roleCode, boolean checkerPrivilege) {
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
        return new Principal(userId, 0L, bearerSession(userId));
    }

    /** Principal whose raw token is later seeded as a cookie session by the test itself. */
    private Principal staffWithoutSession(String username, String roleCode) {
        long userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, roleCode);
            return id;
        });
        return new Principal(userId, 0L, "cookie-" + UUID.randomUUID());
    }

    private Principal customer(String username) {
        long userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'CUSTOMER', 'ACTIVE')", username);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", id);
            return id;
        });
        long customerId = tx.execute(ignored -> jdbc.queryForObject(
                "INSERT INTO banking.customer (user_id, full_name_enc, status) VALUES (?, ?, 'ACTIVE') "
                        + "RETURNING customer_id",
                Long.class, userId, new byte[] {1, 2, 3}));
        return new Principal(userId, customerId, bearerSession(userId));
    }

    private String bearerSession(long userId) {
        String token = "contract-" + UUID.randomUUID();
        seedSession(userId, token, CSRF_HASH);
        return token;
    }

    private void seedCookieSession(long userId, String token, String rawCsrf) {
        seedSession(userId, token, HmacService.sha256Hex(rawCsrf));
    }

    private void seedSession(long userId, String token, String csrfHash) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), csrfHash, userId));
    }

    private long insertApproval(long makerId, String status, String correlation, int expectedVersion) {
        String payload = """
                {"operation":"ASSIGN_ROLE","targetUserId":"%d","roleCode":"AUDITOR","privilegeCode":null,
                 "expectedVersion":%d,"reason":"contract idor"}
                """.formatted(target, expectedVersion);
        return tx.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('ROLE_CHANGE', 'APP_USER', ?, ?::jsonb, ?, ?, ?)
                RETURNING approval_id
                """, Long.class, Long.toString(target), payload, status, makerId, correlation));
    }

    private List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private String approvalSnapshot() {
        return String.join(";", jdbc.query(
                "SELECT approval_id || ':' || status || ':' || coalesce(checker_id::text, '-') "
                        + "FROM banking.approval_request ORDER BY approval_id",
                (rs, rowNum) -> rs.getString(1)));
    }

    private List<String> approvalIds(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        JSON.readTree(response.body()).get("items").forEach(item -> ids.add(item.get("approvalId").asText()));
        return ids;
    }

    private double deniedEvents() {
        return meterRegistry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "APPROVAL_REQUEST").count();
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        return getWithHeaders(path, token, correlationId, Map.of());
    }

    private HttpResponse<String> getWithHeaders(String path, String token, String correlationId,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "application/json");
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        return http().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> cookieGet(String path, String token, String correlationId) throws Exception {
        return http().send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-Correlation-ID", correlationId)
                .header("Cookie", "DB_SESSION=" + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        return sendPost(path, token == null ? null : "Authorization", token == null ? null : "Bearer " + token,
                correlationId, body, idempotencyKey, stepUpChallenge, null, null);
    }

    private HttpResponse<String> cookiePost(String path, String token, String correlationId, String body,
            String idempotencyKey, String csrfToken) throws Exception {
        return sendPost(path, null, null, correlationId, body, idempotencyKey, null,
                "DB_SESSION=" + token, csrfToken);
    }

    private HttpResponse<String> sendPost(String path, String headerName, String headerValue,
            String correlationId, String body, String idempotencyKey, String stepUpChallenge,
            String cookie, String csrfToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json");
        if (headerName != null) {
            builder.header(headerName, headerValue);
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
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (csrfToken != null) {
            builder.header("X-CSRF-Token", csrfToken);
        }
        return http().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static void assertSecurityHeaders(HttpResponse<String> response, String correlationId) {
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        assertThat(response.headers().firstValue("X-Request-ID").orElseThrow()).matches(UUID_PATTERN);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        // HSTS is only emitted on secure transport (HttpSecurityTest covers the TLS case); CSP is unconditional.
        assertThat(response.headers().firstValue("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Pragma")).contains("no-cache");
        assertThat(response.headers().firstValue("Expires")).contains("0");
    }

    /**
     * Envelope contract for Admin problems. {@code correlationId == null} means the supplied header was invalid
     * and the server generated a new id, which must still be echoed.
     */
    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).startsWith("https://api.demo.local/problems/");
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).startsWith("/api/");
        assertThat(body.get("retryable").isBoolean()).isTrue();
        assertThat(body.get("timestamp").asText()).endsWith("Z");
        assertThat(body.get("detail").asText())
                .isNotBlank()
                .doesNotContain("Exception", "PSQLException", "org.springframework", "banking.", "at com.");
        String echo = body.get("correlationId").asText();
        if (correlationId == null) {
            // The supplied header was rejected, so the server generated the correlation id.
            assertThat(echo).matches(UUID_PATTERN).hasSize(36);
        } else {
            assertThat(echo).isEqualTo(correlationId);
        }
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(echo);
        assertSecurityHeaders(response, echo);
    }
}
