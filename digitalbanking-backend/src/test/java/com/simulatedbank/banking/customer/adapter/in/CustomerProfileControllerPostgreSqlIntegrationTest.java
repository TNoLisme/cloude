package com.simulatedbank.banking.customer.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.customer.application.CustomerPiiService;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * BE-CUS-IMP-05: HTTP integration của `GET /api/v1/customers/me` trên security chain thật
 * (session filter, rule `ACCOUNT_READ_SELF`, correlation filter, Problem writer) và schema
 * PostgreSQL thật (Flyway V1–V5). Không mock bỏ bước bảo mật: 401/403 do shared security layer
 * sinh ra, 404 theo hợp đồng binding đã duyệt, 200 đúng JSON `CustomerProfile` (07 §7.2.9/§8.2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerProfileControllerPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("customer_profile_it")
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
        // Tách rate-limit ra khỏi kịch bản chức năng (cùng biện pháp với identity HTTP tests).
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CustomerPiiService piiService;

    @Test
    @Order(1)
    void authenticatedOwnerReceivesExactCustomerProfileContract(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-owner", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(userId, "Nguyễn Văn A", "+84900000123", "nva.synthetic@example.com",
                CustomerStatus.ACTIVE);
        String token = "self-owner-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me?customerId=999999&userId=777", token, "cus-self-200")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).isEqualTo(Set.of(
                "customerId", "userId", "fullName", "phoneMasked", "emailMasked", "status", "createdAt",
                "updatedAt"));
        assertThat(body.get("customerId").asText()).isEqualTo(Long.toString(created.getCustomerId()));
        assertThat(body.get("userId").asText()).isEqualTo(Long.toString(userId));
        assertThat(body.get("fullName").asText()).isEqualTo("Nguyễn Văn A");
        assertThat(body.get("phoneMasked").asText()).isEqualTo("********123");
        assertThat(body.get("emailMasked").asText()).isEqualTo("n***@example.com");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        // Query parameter không thể đổi chủ thể: response vẫn là chính chủ (anti-IDOR theo cấu trúc).
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("cus-self-200");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        // Instant UTC serialization (07 §7.2.9): parse được và khớp hàng đã lưu.
        Instant storedUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM banking.customer WHERE customer_id = ?", Instant.class,
                created.getCustomerId());
        assertThat(Instant.parse(body.get("updatedAt").asText())).isEqualTo(storedUpdatedAt);
        assertThat(Instant.parse(body.get("createdAt").asText())).isEqualTo(created.getCreatedAt());
    }

    @Test
    @Order(2)
    void missingAuthenticationFailsClosedWithSessionExpiredProblem() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", null, "cus-self-anon").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "cus-self-anon");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"digital-banking\"");
    }

    @Test
    @Order(3)
    void invalidTokenFailsClosedWithoutLeakingExistence() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", "invalid-" + UUID.randomUUID(), "cus-self-invalid")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "cus-self-invalid");
    }

    @Test
    @Order(4)
    void expiredSessionIsRejectedBeforeAnyCustomerLookup(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-expired", "CUSTOMER", "CUSTOMER");
        piiService.create(userId, "Hồ Sơ Hết Hạn", null, null, CustomerStatus.ACTIVE);
        String token = "self-expired-" + UUID.randomUUID();
        seedExpiredSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-expired").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "cus-self-expired");
        assertThat(countCustomers(jdbc, "self-expired")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void authenticatedWithoutSelfReadAuthorityIsForbidden(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        // OPERATIONS (staff) chỉ có ACCOUNT_READ, không có ACCOUNT_READ_SELF → chain chặn 403.
        long staffId = seedUserWithRoles(tx, jdbc, "self-staff", "STAFF", "OPERATIONS");
        String token = "self-staff-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, staffId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-403").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 403, "AUTH_FORBIDDEN", "cus-self-403");
    }

    @Test
    @Order(6)
    void authenticatedIdentityWithoutCustomerRowReturns404AndNeverStores(@Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-no-row", "CUSTOMER", "CUSTOMER");
        String token = "self-no-row-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long customersBefore = countAllCustomers(jdbc);
        long usersBefore = countAllUsers(jdbc);

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-404").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 404, "CUSTOMER_NOT_FOUND", "cus-self-404");
        // GET thuần đọc: không tự tạo Customer, không placeholder, không binding mới.
        assertThat(countAllCustomers(jdbc)).isEqualTo(customersBefore);
        assertThat(countAllUsers(jdbc)).isEqualTo(usersBefore);
        assertThat(countSessions(jdbc, "self-no-row")).isEqualTo(1);
    }

    @Test
    @Order(7)
    void otherCustomerExistenceIsNotDisclosedToUnboundIdentity(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long ownerA = seedUserWithRoles(tx, jdbc, "self-leak-a", "CUSTOMER", "CUSTOMER");
        piiService.create(ownerA, "Chủ Nhân A", "+84900000456", "a.synthetic@example.com", CustomerStatus.ACTIVE);
        long stranger = seedUserWithRoles(tx, jdbc, "self-stranger", "CUSTOMER", "CUSTOMER");
        String token = "self-stranger-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, stranger, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me?customerId=" + ownerA + "&userId=" + ownerA,
                        token, "cus-self-leak").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 404, "CUSTOMER_NOT_FOUND", "cus-self-leak");
        assertThat(response.body()).doesNotContain("Chủ Nhân A", "a.synthetic@example.com", "84900000456");
    }

    @Test
    @Order(8)
    void invalidCorrelationIdIsRejectedBySharedMiddlewareBeforeSecurity() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", null, "bad correlation id with spaces").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 400, "VALIDATION_ERROR", null);
    }

    @Test
    @Order(9)
    void generatedCorrelationIdIsReturnedWhenClientOmitsHeader(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-nocorr", "CUSTOMER", "CUSTOMER");
        piiService.create(userId, "Không Correlation", null, null, CustomerStatus.ACTIVE);
        String token = "self-nocorr-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, null).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String correlation = response.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertThat(correlation).matches("[0-9a-f-]{36}");
    }

    @Test
    @Order(10)
    void retrievalLeavesExistingCustomerDataUnchanged(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-readonly", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(userId, "Đọc Thuần", "+84900000789", "r.synthetic@example.com",
                CustomerStatus.ACTIVE);
        String token = "self-readonly-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Row before = row(jdbc, created.getCustomerId());

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-ro").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        Row after = row(jdbc, created.getCustomerId());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.fullNameEnc()).isEqualTo(before.fullNameEnc());
        assertThat(after.phoneEnc()).isEqualTo(before.phoneEnc());
        assertThat(after.emailEnc()).isEqualTo(before.emailEnc());
        assertThat(after.status()).isEqualTo(before.status());
    }

    @Test
    @Order(11)
    void inactiveCustomerStillReadsItsOwnProfileWithRealStatus(@Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-inactive", "CUSTOMER", "CUSTOMER");
        var created = piiService.create(userId, "Hồ Sơ Ngừng", null, null, CustomerStatus.INACTIVE);
        String token = "self-inactive-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-inactive").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(body.get("customerId").asText()).isEqualTo(Long.toString(created.getCustomerId()));
        assertThat(body.get("phoneMasked").isNull()).isTrue();
        assertThat(body.get("emailMasked").isNull()).isTrue();
    }

    @Test
    @Order(12)
    void disabledIdentityIsRejectedBeforeCustomerResolution(@Autowired JdbcTemplate jdbc)
            throws Exception {
        TransactionTemplate tx = tx(jdbc);
        long userId = seedUserWithRoles(tx, jdbc, "self-disabled", "CUSTOMER", "CUSTOMER");
        piiService.create(userId, "Hồ Sơ Disabled", null, null, CustomerStatus.ACTIVE);
        String token = "self-disabled-" + UUID.randomUUID();
        seedActiveSession(tx, jdbc, userId, token);
        tx.executeWithoutResult(ignored ->
                jdbc.update("UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = ?", userId));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = http.send(
                request(port, "/api/v1/customers/me", token, "cus-self-disabled").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "cus-self-disabled");
        assertThat(countCustomers(jdbc, "self-disabled")).isEqualTo(1);
    }

    @Test
    @Order(13)
    @DisplayName("BE-CUS-IMP-08 security: malformed and oversized Bearer credentials fail closed without disclosure")
    void malformedAndOversizedBearerCredentialsFailClosedWithoutDisclosure() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<String> credentials = List.of(
                "Basic c3ludGhldGljOnNlY3JldA==",
                "Bearer malformed$synthetic-secret",
                "Bearer " + "x".repeat(513));

        for (int index = 0; index < credentials.size(); index++) {
            String credential = credentials.get(index);
            String correlationId = "cus-self-malformed-" + index;
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/customers/me"))
                            .header("Authorization", credential)
                            .header("X-Correlation-ID", correlationId)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertProblem(response, 401, "AUTH_SESSION_EXPIRED", correlationId);
            assertThat(response.body())
                    .doesNotContain(credential, "malformed$synthetic-secret", "c3ludGhldGljOnNlY3JldA==")
                    .doesNotContain("stackTrace", "org.springframework", "banking.session");
        }
    }

    // ------------------------------------------------------------------ helpers

    private TransactionTemplate tx(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static Set<String> fieldNames(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static HttpRequest.Builder request(int port, String path, String bearerToken, String correlationId) {
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
        assertThat(body.get("code").asText()).isEqualTo(code);
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
                HmacService.sha256Hex(token), "a".repeat(64), userId));
    }

    private void seedExpiredSession(TransactionTemplate tx, JdbcTemplate jdbc, long userId, String token) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "last_activity_at, idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() - interval '5 minutes', now() - interval '1 minute', "
                        + "now() + interval '1 hour')",
                HmacService.sha256Hex(token), "b".repeat(64), userId));
    }

    private long countCustomers(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking.customer c JOIN banking.app_user u ON u.user_id = c.user_id "
                        + "WHERE u.username = ?", Long.class, username);
    }

    private long countAllCustomers(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM banking.customer", Long.class);
    }

    private long countAllUsers(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM banking.app_user", Long.class);
    }

    private long countSessions(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking.session s JOIN banking.app_user u ON u.user_id = s.user_id "
                        + "WHERE u.username = ?", Long.class, username);
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

    private record Row(Instant updatedAt, byte[] fullNameEnc, byte[] phoneEnc, byte[] emailEnc, String status) {
    }
}
