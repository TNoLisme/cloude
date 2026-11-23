package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * BE-ADM-API-03: HTTP evidence for {@code GET /api/v1/admin/approvals} and
 * {@code GET /api/v1/admin/approvals/{approvalId}} on the real security chain and PostgreSQL schema.
 *
 * <p>Proves scope is a SQL predicate (maker sees own, checker sees the types they may decide), filters
 * cannot widen it, pagination counts stay scoped, detail hides unknown and out-of-scope ids behind the same
 * 404, payloads are masked, and reads neither mutate nor audit business state.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class ApprovalReadHttpPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_read");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    private TransactionTemplate tx;
    private long makerA;
    private long makerB;
    private long checkerLimitOnly;
    private long checkerAdmin;
    private long customer;
    private long target;
    private long roleByB;
    private long limitByA;
    private long limitByB;
    private long roleByA;
    private String makerAToken;
    private String checkerLimitToken;
    private String checkerAdminToken;
    private String customerToken;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record WHERE approval_id IN "
                    + "(SELECT approval_id FROM banking.approval_request)");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
        makerA = user(jdbc, "adm-read-a-" + suffix, "OPERATIONS");
        makerB = user(jdbc, "adm-read-b-" + suffix, "ADMIN");
        checkerLimitOnly = user(jdbc, "adm-read-c1-" + suffix, "OPERATIONS");
        checkerAdmin = user(jdbc, "adm-read-c2-" + suffix, "ADMIN");
        customer = user(jdbc, "adm-read-cus-" + suffix, "CUSTOMER");
        target = user(jdbc, "adm-read-target-" + suffix, "OPERATIONS");
        grantChecker(jdbc, checkerLimitOnly);
        grantChecker(jdbc, checkerAdmin);

        roleByB = insertApproval(jdbc, "ROLE_CHANGE", "APP_USER", Long.toString(target), rolePayload(target),
                "PENDING", makerB, null, null, "adm-read-role-b", agoMinutes(4));
        limitByA = insertApproval(jdbc, "LIMIT_CHANGE", "CUSTOMER", "2001", limitPayload(), "PENDING",
                makerA, null, null, "adm-read-limit-a", agoMinutes(3));
        limitByB = insertApproval(jdbc, "LIMIT_CHANGE", "CUSTOMER", "2001", limitPayload(), "PENDING",
                makerB, null, null, "adm-read-limit-b", agoMinutes(2));
        roleByA = insertApproval(jdbc, "ROLE_CHANGE", "APP_USER", Long.toString(target), rolePayload(target),
                "REJECTED", makerA, checkerAdmin, "out of policy", "adm-read-role-a", agoMinutes(1),
                agoMinutes(1).plusSeconds(10));

        makerAToken = session(jdbc, makerA);
        checkerLimitToken = session(jdbc, checkerLimitOnly);
        checkerAdminToken = session(jdbc, checkerAdmin);
        customerToken = session(jdbc, customer);
    }

    @Test
    @DisplayName("API-03: anonymous read is 401 and is not audited as a 403 denial")
    void anonymousReadIs401(CapturedOutput output) throws Exception {
        assertProblem(get("/api/v1/admin/approvals", null, "adm-read-anon"), 401, "AUTH_SESSION_EXPIRED",
                "adm-read-anon");
        assertProblem(get("/api/v1/admin/approvals/" + roleByB, null, "adm-read-anon-2"), 401,
                "AUTH_SESSION_EXPIRED", "adm-read-anon-2");
        assertThat(output).doesNotContain("\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @DisplayName("API-03: a customer principal is forbidden and the denial is audited")
    void customerIsForbiddenAndAudited(CapturedOutput output) throws Exception {
        double before = deniedEvents();

        assertProblem(get("/api/v1/admin/approvals", customerToken, "adm-read-403"), 403, "AUTH_FORBIDDEN",
                "adm-read-403");

        assertThat(deniedEvents()).isEqualTo(before + 1);
        assertThat(output).contains("\"action\":\"SECURITY_ACCESS_DENIED\"",
                "\"entity_type\":\"APPROVAL_REQUEST\"", "\"correlation_id\":\"adm-read-403\"");
        assertThat(output).doesNotContain(customerToken);
    }

    @Test
    @DisplayName("API-03: maker sees only own approvals with scoped page metadata and no payload")
    void makerSeesOnlyOwnApprovals(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/api/v1/admin/approvals", makerAToken, "adm-read-own");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("adm-read-own");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("page").get("totalElements").asLong()).isEqualTo(2);
        assertThat(body.get("page").get("number").asInt()).isZero();
        assertThat(body.get("page").get("size").asInt()).isEqualTo(20);
        assertThat(body.get("page").get("first").asBoolean()).isTrue();
        assertThat(body.get("page").get("last").asBoolean()).isTrue();
        assertThat(Instant.parse(body.get("asOf").asText())).isNotNull();
        TreeSet<String> ids = new TreeSet<>();
        body.get("items").forEach(item -> ids.add(item.get("approvalId").asText()));
        assertThat(ids).containsExactly(Long.toString(limitByA), Long.toString(roleByA));
        TreeSet<String> fields = new TreeSet<>();
        body.get("items").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("appliedAt", "approvalId", "checker", "correlationId", "createdAt",
                "decidedAt", "decisionNote", "maker", "requestType", "status", "targetId", "targetType");
        assertThat(response.body()).doesNotContain("payload", "expectedVersion");
        // Read-only path: no business audit fact and no state change.
        assertThat(output).doesNotContain("\"action\":\"ROLE_CHANGE_", "\"action\":\"LIMIT_CHANGE_",
                "\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @DisplayName("API-03: filters work individually and inside the scope")
    void filtersWorkInsideScope(@Autowired JdbcTemplate jdbc) throws Exception {
        assertIds(get("/api/v1/admin/approvals?requestType=ROLE_CHANGE", makerAToken, "adm-read-f1"),
                roleByA);
        assertIds(get("/api/v1/admin/approvals?status=PENDING", makerAToken, "adm-read-f2"), limitByA);
        assertIds(get("/api/v1/admin/approvals?makerId=" + makerB, makerAToken, "adm-read-f3"));
        assertIds(get("/api/v1/admin/approvals?checkerId=" + checkerAdmin, makerAToken, "adm-read-f4"), roleByA);
        assertIds(get("/api/v1/admin/approvals?from=" + createdAt(jdbc, limitByA), makerAToken, "adm-read-f5"),
                limitByA, roleByA);
        assertIds(get("/api/v1/admin/approvals?to=" + createdAt(jdbc, roleByA), makerAToken, "adm-read-f6"),
                limitByA);
        assertIds(get("/api/v1/admin/approvals?requestType=ROLE_CHANGE&status=REJECTED", makerAToken,
                "adm-read-f7"), roleByA);

        // Stable pagination and ordering for identical timestamps are covered by the DB-02 suite; here the
        // scoped count must follow the page rather than the whole table.
        HttpResponse<String> paged = get("/api/v1/admin/approvals?page=0&size=1&sort=createdAt,asc", makerAToken,
                "adm-read-page");
        assertIds(paged, limitByA);
        JsonNode page = JSON.readTree(paged.body()).get("page");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("first").asBoolean()).isTrue();
        assertThat(page.get("last").asBoolean()).isFalse();
        assertIds(get("/api/v1/admin/approvals?page=1&size=1&sort=createdAt,asc", makerAToken, "adm-read-page2"),
                roleByA);
    }

    @Test
    @DisplayName("API-03: malformed filters and pagination are standard 400s")
    void malformedQueriesAre400() throws Exception {
        for (String query : new String[] {"requestType=RETENTION_HOLD", "status=EXPIRED", "makerId=0",
                "makerId=abc", "page=-1", "page=abc", "size=0", "size=101", "sort=status,desc",
                "sort=createdAt", "from=yesterday", "from=2026-11-18T00:00:00Z&to=2026-11-17T00:00:00Z"}) {
            assertProblem(get("/api/v1/admin/approvals?" + query, makerAToken, "adm-read-bad"),
                    400, "VALIDATION_ERROR", "adm-read-bad");
        }
    }

    @Test
    @DisplayName("API-03: detail returns the masked payload for an in-scope approval")
    void detailReturnsMaskedPayload() throws Exception {
        HttpResponse<String> response = get("/api/v1/admin/approvals/" + roleByA, makerAToken, "adm-read-detail");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("approvalId").asText()).isEqualTo(Long.toString(roleByA));
        assertThat(body.get("requestType").asText()).isEqualTo("ROLE_CHANGE");
        assertThat(body.get("status").asText()).isEqualTo("REJECTED");
        assertThat(body.get("payload").get("operation").asText()).isEqualTo("ASSIGN_ROLE");
        assertThat(body.get("payload").has("expectedVersion")).isFalse();
        assertThat(body.has("result")).isFalse();
        assertThat(response.body()).doesNotContain("expectedVersion", "synthetic-not-a-real-hash");
    }

    @Test
    @DisplayName("API-03: out-of-scope and unknown details are the same 404")
    void outOfScopeAndUnknownDetailAre404(CapturedOutput output) throws Exception {
        assertProblem(get("/api/v1/admin/approvals/" + roleByB, makerAToken, "adm-read-404a"), 404,
                "APPROVAL_NOT_FOUND", "adm-read-404a");
        assertProblem(get("/api/v1/admin/approvals/9000001", makerAToken, "adm-read-404b"), 404,
                "APPROVAL_NOT_FOUND", "adm-read-404b");
        assertThat(output).doesNotContain("\"action\":\"SECURITY_ACCESS_DENIED\"");
    }

    @Test
    @DisplayName("API-03: checker scope is limited to the approval types they may decide")
    void checkerScopeIsTypeBounded() throws Exception {
        // LIMIT-only checker: own (none) + every LIMIT_CHANGE row; ROLE_CHANGE rows stay hidden.
        HttpResponse<String> limitOnly = get("/api/v1/admin/approvals", checkerLimitToken, "adm-read-c1");
        assertThat(limitOnly.statusCode()).isEqualTo(200);
        TreeSet<String> ids = new TreeSet<>();
        JSON.readTree(limitOnly.body()).get("items")
                .forEach(item -> ids.add(item.get("approvalId").asText()));
        assertThat(ids).withFailMessage(() -> "body=" + limitOnly.body())
                .containsExactly(Long.toString(limitByA), Long.toString(limitByB));
        assertThat(JSON.readTree(limitOnly.body()).get("page").get("totalElements").asLong()).isEqualTo(2);
        assertIds(get("/api/v1/admin/approvals?requestType=LIMIT_CHANGE", checkerLimitToken, "adm-read-c2"),
                limitByA, limitByB);
        assertProblem(get("/api/v1/admin/approvals/" + roleByB, checkerLimitToken, "adm-read-c3"), 404,
                "APPROVAL_NOT_FOUND", "adm-read-c3");
        assertThat(get("/api/v1/admin/approvals/" + limitByB, checkerLimitToken, "adm-read-c4").statusCode())
                .isEqualTo(200);

        // ADMIN role + CHECKER privilege has ROLE_MANAGE and LIMIT_MANAGE: sees all four rows.
        HttpResponse<String> admin = get("/api/v1/admin/approvals", checkerAdminToken, "adm-read-c5");
        assertThat(JSON.readTree(admin.body()).get("page").get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("API-03: reads never mutate approvals or generate business audit facts")
    void readsDoNotMutateState(CapturedOutput output, @Autowired JdbcTemplate jdbc) throws Exception {
        String before = snapshot(jdbc);

        get("/api/v1/admin/approvals", makerAToken, "adm-read-ro1");
        get("/api/v1/admin/approvals/" + roleByA, makerAToken, "adm-read-ro2");
        get("/api/v1/admin/approvals", checkerAdminToken, "adm-read-ro3");
        assertProblem(get("/api/v1/admin/approvals/" + roleByB, makerAToken, "adm-read-ro4"), 404,
                "APPROVAL_NOT_FOUND", "adm-read-ro4");

        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(output).doesNotContain("\"action\":\"ROLE_CHANGE_", "\"action\":\"LIMIT_CHANGE_");
    }

    // ------------------------------------------------------------------ helpers

    private double deniedEvents() {
        return meterRegistry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "APPROVAL_REQUEST").count();
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Correlation-ID", correlationId)
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertIds(HttpResponse<String> response, long... expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        TreeSet<String> ids = new TreeSet<>();
        JSON.readTree(response.body()).get("items").forEach(item -> ids.add(item.get("approvalId").asText()));
        TreeSet<String> expectedIds = new TreeSet<>();
        for (long id : expected) {
            expectedIds.add(Long.toString(id));
        }
        assertThat(ids).withFailMessage(() -> "body=" + response.body()).isEqualTo(expectedIds);
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("detail").asText()).doesNotContain("Exception", "PSQLException", "banking.");
    }

    private long user(JdbcTemplate jdbc, String username, String roleCode) {
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

    private void grantChecker(JdbcTemplate jdbc, long userId) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", userId));
    }

    private String session(JdbcTemplate jdbc, long userId) {
        String token = "adm-read-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }

    private long insertApproval(JdbcTemplate jdbc, String requestType, String targetType, String targetId,
            String payload, String status, long makerId, Long checkerId, String note, String correlationId,
            Instant createdAt) {
        return insertApproval(jdbc, requestType, targetType, targetId, payload, status, makerId, checkerId, note,
                correlationId, createdAt, null);
    }

    private long insertApproval(JdbcTemplate jdbc, String requestType, String targetType, String targetId,
            String payload, String status, long makerId, Long checkerId, String note, String correlationId,
            Instant createdAt, Instant decidedAt) {
        return tx.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     decision_note, correlation_id, created_at, decided_at, applied_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, NULL)
                RETURNING approval_id
                """, Long.class, requestType, targetType, targetId, payload, status, makerId, checkerId, note,
                correlationId, Timestamp.from(createdAt),
                decidedAt == null ? null : Timestamp.from(decidedAt)));
    }

    private Instant createdAt(JdbcTemplate jdbc, long approvalId) {
        return jdbc.queryForObject("SELECT created_at FROM banking.approval_request WHERE approval_id = ?",
                Instant.class, approvalId);
    }

    private String snapshot(JdbcTemplate jdbc) {
        return jdbc.query("SELECT approval_id || ':' || status || ':' || coalesce(checker_id::text, '-') "
                + "FROM banking.approval_request ORDER BY approval_id", (rs, rowNum) -> rs.getString(1)).toString();
    }

    private static Instant agoMinutes(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private static String rolePayload(long targetUserId) {
        return """
                {"operation":"ASSIGN_ROLE","targetUserId":"%d","roleCode":"AUDITOR","privilegeCode":null,
                 "expectedVersion":3,"reason":"synthetic role change"}
                """.formatted(targetUserId);
    }

    private static String limitPayload() {
        return """
                {"customerId":"2001","accountId":null,"perTransactionLimit":"5000000.00",
                 "dailyLimit":"20000000.00","currency":"VND","effectiveFrom":null,"reason":"synthetic limit"}
                """;
    }
}
