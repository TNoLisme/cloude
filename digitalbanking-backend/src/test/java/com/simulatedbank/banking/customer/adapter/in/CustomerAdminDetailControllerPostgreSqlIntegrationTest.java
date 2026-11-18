package com.simulatedbank.banking.customer.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.customer.application.CustomerPiiService;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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
 * BE-CUS-IMP-06: HTTP integration của `API-ADM-01` `GET /api/v1/admin/customers/{customerId}`
 * trên security chain thật (session filter, rule `CUSTOMER_READ`, correlation filter, Problem
 * writer, security-access-denied auditor) và schema PostgreSQL thật (Flyway V1–V5). Không mock
 * bỏ bước bảo mật: 401/403 do shared security layer sinh ra, 200 đúng JSON `CustomerProfile`
 * với fullName masked (DEC-CUS-005), từ chối được audit qua metric của
 * {@code SecurityAccessDeniedAuditor}/customer audit adapter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerAdminDetailControllerPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("customer_admin_it")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CustomerPiiService piiService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("AC-023/AC-027/CT-API-017: authorized staff receives only masked Customer PII")
    @Order(1)
    void authorizedStaffReceivesMaskedCustomerProfileContract(@Autowired JdbcTemplate jdbc,
            CapturedOutput output) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerId = seedUserWithRoles(tx, jdbc, "adm-owner-1", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(ownerId, "Nguyễn Văn An", "+84900000123",
                "nva.synthetic@example.com", CustomerStatus.ACTIVE);
        long staffId = seedUserWithRoles(tx, jdbc, "adm-staff-1", "STAFF", "OPERATIONS");
        String token = "adm-staff-1-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/" + created.getCustomerId(), token,
                        "adm-200").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).isEqualTo(Set.of(
                "customerId", "userId", "fullName", "phoneMasked", "emailMasked", "status",
                "createdAt", "updatedAt"));
        assertThat(body.get("customerId").asText()).isEqualTo(Long.toString(created.getCustomerId()));
        assertThat(body.get("userId").asText()).isEqualTo(Long.toString(ownerId));
        // fullName masked (DEC-CUS-005); raw fullName/PII không rò vào response.
        assertThat(body.get("fullName").asText()).isEqualTo("N*** V*** A***");
        assertThat(response.body()).doesNotContain("Nguyễn", "+84900000123", "84900000123");
        assertThat(body.get("phoneMasked").asText()).isEqualTo("********123");
        assertThat(body.get("emailMasked").asText()).isEqualTo("n***@example.com");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("adm-200");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(output).doesNotContain(
                "Nguyễn Văn An", "+84900000123", "84900000123", "nva.synthetic@example.com", token);
    }

    @Test
    @Order(2)
    void adminRoleStaffIsAlsoAuthorized(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerId = seedUserWithRoles(tx, jdbc, "adm-owner-2", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(ownerId, "Trần Minh Bình", null, null, CustomerStatus.ACTIVE);
        long adminId = seedUserWithRoles(tx, jdbc, "adm-staff-2", "STAFF", "ADMIN");
        String token = "adm-staff-2-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, adminId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/" + created.getCustomerId(), token,
                        "adm-admin").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("fullName").asText()).isEqualTo("T*** M*** B***");
        assertThat(body.get("phoneMasked").isNull()).isTrue();
        assertThat(body.get("emailMasked").isNull()).isTrue();
    }

    @Test
    @Order(3)
    void missingAuthenticationIsRejectedBeforeAnyLookup() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/1", null, "adm-anon").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "adm-anon");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"digital-banking\"");
    }

    @Test
    @Order(4)
    @DisplayName("AC-024/CT-API-015: cross-customer admin-path access is denied, audited, and discloses no PII")
    void customerRoleIsDeniedAndAccessDeniedIsAudited(@Autowired JdbcTemplate jdbc,
            CapturedOutput output) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerId = seedUserWithRoles(tx, jdbc, "adm-owner-3", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(ownerId, "Khách Bị Che", "+84900000456",
                "kbc.synthetic@example.com", CustomerStatus.ACTIVE);
        long customerId = created.getCustomerId();
        long attackerId = seedUserWithRoles(tx, jdbc, "adm-customer-3", "CUSTOMER", "CUSTOMER");
        String token = "adm-customer-3-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, attackerId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        double deniedBefore = accessDeniedCounter(meterRegistry, "CUSTOMER").count();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/" + customerId, token, "adm-403")
                        .header("X-Role", "ADMIN")
                        .header("X-Permissions", "CUSTOMER_READ")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 403, "AUTH_FORBIDDEN", "adm-403");
        // CUSTOMER bị từ chối phải tạo audit record (SECURITY_ACCESS_DENIED, C3 best-effort —
        // chứng minh bằng metric của SecurityAccessDeniedAuditor; persistence audit_log thuộc
        // module audit chưa có, DEC-ID-003/BE-ID-IMP-13).
        var counter = accessDeniedCounter(meterRegistry, "CUSTOMER");
        assertThat(counter.count()).isGreaterThanOrEqualTo(deniedBefore + 1);
        // Response không tiết lộ PII của customer bị truy cập trái phép.
        assertThat(response.body()).doesNotContain("Khách", "84900000456", "kbc.synthetic");
        // Role/permission do client giả mạo bị bỏ qua; principal session vẫn bị chain từ chối.
        // Kiểm tra stdout JSON thật sau SafeJsonLogEncoder, không chỉ event trước encoder.
        assertThat(output).contains(
                "\"event\":\"SECURITY_AUDIT\"",
                "\"action\":\"SECURITY_ACCESS_DENIED\"",
                "\"outcome\":\"REJECTED\"",
                "\"actor_user_id\":" + attackerId,
                "\"actor_roles\":\"CUSTOMER\"",
                "\"entity_type\":\"CUSTOMER\"",
                "\"entity_id\":\"" + customerId + "\"",
                "\"reason_code\":\"AUTH_FORBIDDEN\"",
                "\"correlation_id\":\"adm-403\"");
        assertThat(output).doesNotContain("Khách Bị Che", "kbc.synthetic@example.com", "+84900000456");
    }

    @Test
    @Order(5)
    @DisplayName("CT-API-016: staff without CUSTOMER_READ is denied and audited before lookup")
    void staffWithoutCustomerReadPermissionIsDeniedAndAudited(@Autowired JdbcTemplate jdbc)
            throws Exception {
        // Seed A.2 (04 §A.2/07 §3.4): AUDITOR không có CUSTOMER_READ → 403 fail-closed.
        // Ghi nhận xung đột 07 §8.2.4 (Auditor "với CUSTOMER_READ") tại BE-CUS-IMP-06.md §G.
        TransactionTemplate tx = tx(jdbc);
        long auditorId = seedUserWithRoles(tx, jdbc, "adm-auditor-5", "STAFF", "AUDITOR");
        String token = "adm-auditor-5-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, auditorId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        double deniedBefore = accessDeniedCounter(meterRegistry, "CUSTOMER").count();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/999999999", token, "adm-aud-403").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 403, "AUTH_FORBIDDEN", "adm-aud-403");
        assertThat(accessDeniedCounter(meterRegistry, "CUSTOMER").count())
                .isGreaterThanOrEqualTo(deniedBefore + 1);
    }

    @Test
    @Order(6)
    void invalidCustomerIdIsRejectedAsValidationProblem(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long staffId = seedUserWithRoles(tx, jdbc, "adm-staff-6", "STAFF", "OPERATIONS");
        String token = "adm-staff-6-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> letters = http.send(
                request(port, "/api/v1/admin/customers/abc", token, "adm-400a").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> zero = http.send(
                request(port, "/api/v1/admin/customers/0", token, "adm-400b").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> overflow = http.send(
                request(port, "/api/v1/admin/customers/99999999999999999999", token, "adm-400c")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(letters, 400, "VALIDATION_ERROR", "adm-400a");
        assertProblem(zero, 400, "VALIDATION_ERROR", "adm-400b");
        assertProblem(overflow, 400, "VALIDATION_ERROR", "adm-400c");
    }

    @Test
    @Order(7)
    void nonexistentCustomerReturnsNotFoundWithoutDisclosure(@Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long staffId = seedUserWithRoles(tx, jdbc, "adm-staff-7", "STAFF", "OPERATIONS");
        String token = "adm-staff-7-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/424242", token, "adm-404").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 404, "CUSTOMER_NOT_FOUND", "adm-404");
    }

    @Test
    @Order(8)
    void inactiveCustomerIsReturnedWithRealStatus(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerId = seedUserWithRoles(tx, jdbc, "adm-owner-8", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(ownerId, "Hồ Sơ Ngừng", null, null, CustomerStatus.INACTIVE);
        long staffId = seedUserWithRoles(tx, jdbc, "adm-staff-8", "STAFF", "OPERATIONS");
        String token = "adm-staff-8-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/" + created.getCustomerId(), token,
                        "adm-inactive").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(body.get("phoneMasked").isNull()).isTrue();
        assertThat(body.get("emailMasked").isNull()).isTrue();
    }

    @Test
    @Order(9)
    void staffRetrievalLeavesStoredCiphertextAndTimestampsUnchanged(@Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerId = seedUserWithRoles(tx, jdbc, "adm-owner-9", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(ownerId, "Đọc Thuần Staff", "+84900000789",
                "dts.synthetic@example.com", CustomerStatus.ACTIVE);
        long staffId = seedUserWithRoles(tx, jdbc, "adm-staff-9", "STAFF", "OPERATIONS");
        String token = "adm-staff-9-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Row before = row(jdbc, created.getCustomerId());

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/admin/customers/" + created.getCustomerId(), token,
                        "adm-ro").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Row after = row(jdbc, created.getCustomerId());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.fullNameEnc()).isEqualTo(before.fullNameEnc());
        assertThat(after.phoneEnc()).isEqualTo(before.phoneEnc());
        assertThat(after.emailEnc()).isEqualTo(before.emailEnc());
        assertThat(after.status()).isEqualTo(before.status());
    }

    // ------------------------------------------------------------------ helpers

    private static io.micrometer.core.instrument.Counter accessDeniedCounter(MeterRegistry registry,
            String entityType) {
        return registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", entityType);
    }

    private TransactionTemplate tx(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static Set<String> fieldNames(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static HttpRequest.Builder request(int port, String path, String bearerToken,
            String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return builder;
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).contains(
                "type", "title", "status", "detail", "instance", "code", "correlationId",
                "timestamp", "retryable");
        assertThat(body.get("type").asText()).startsWith("https://");
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asText())
                .isNotBlank()
                .doesNotContain("Exception", "org.springframework", "PSQLException", "banking.");
        assertThat(body.get("instance").asText()).startsWith("/api/");
        assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
        assertThat(body.get("retryable").asBoolean())
                .isEqualTo("AUTH_SESSION_EXPIRED".equals(code));
        assertThat(body.get("code").asText()).isEqualTo(code);
        if (correlationId != null) {
            assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        }
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    }

    private long seedUserWithRoles(TransactionTemplate tx, JdbcTemplate jdbc, String username,
            String userType, String... roleCodes) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', ?, 'ACTIVE')", username, userType);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            for (String roleCode : roleCodes) {
                jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                        + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            }
            return userId;
        });
    }

    private void seedActiveSession(TransactionTemplate tx, JdbcTemplate jdbc, long userId, String token) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
    }

    private Row row(JdbcTemplate jdbc, long customerId) {
        return jdbc.queryForObject(
                "SELECT updated_at, full_name_enc, phone_enc, email_enc, status FROM banking.customer "
                        + "WHERE customer_id = ?",
                (rs, rowNum) -> new Row(
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBytes("full_name_enc"),
                        rs.getBytes("phone_enc"),
                        rs.getBytes("email_enc"),
                        rs.getString("status")),
                customerId);
    }

    private record Row(Instant updatedAt, byte[] fullNameEnc, byte[] phoneEnc, byte[] emailEnc,
            String status) {
    }
}
