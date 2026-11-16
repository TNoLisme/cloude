package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ID-IMP-04: integration bảo mật CSRF/RBAC/actor/IDOR qua HTTP thật (filter chain + SPI
 * `IdentitySessionAuthenticator` thật, PostgreSQL Testcontainers — design/04 §4.3/§5, 07 §8.1).
 * Không mock chứng minh invariant; token/proof lấy từ luồng login thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(IdentitySecurityIntegrationTest.LoginGatewayConfiguration.class)
class IdentitySecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        // Bộ test dùng chung IP loopback với >10 login; quota per-IP/username đã được kiểm
        // chứng riêng ở HttpSecurityComponentsTest — nâng quota để không nhiễu 429.
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private Argon2PasswordHasher hasher;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LoginGateway gateway;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.base = "http://127.0.0.1:" + port;
    }

    // ------------------------------------------------------------------ helpers

    private long seedUser(String username, String password, String userType, String... roleCodes) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                            + "VALUES (?, ?, ?, 'ACTIVE')",
                    username, hasher.hash(password.toCharArray()), userType);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            for (String roleCode : roleCodes) {
                jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                        + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            }
            return userId;
        });
    }

    private record HttpResult(int status, JsonNode body, List<String> setCookie,
            Map<String, List<String>> headers) {
    }

    private HttpResult send(String method, String path, String cookie, Map<String, String> headers,
            String payload) throws Exception {
        List<String[]> headerPairs = headers.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue()}).toList();
        return sendMulti(method, path, cookie, headerPairs, payload);
    }

    /** Cho phép trùng tên header (2 dòng X-CSRF-Token như một request thật của attacker). */
    private HttpResult sendMulti(String method, String path, String cookie, List<String[]> headers,
            String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        headers.forEach(h -> builder.header(h[0], h[1]));
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isEmpty()
                ? JSON.nullNode() : JSON.readTree(response.body());
        return new HttpResult(response.statusCode(), body, response.headers().allValues("Set-Cookie"),
                response.headers().map());
    }

    /** Login thật qua HTTP; trả credential phiên (cookie + csrf raw từ AuthResponse). */
    private record Credentials(long userId, String cookie, String csrf) {
    }

    private Credentials login(String username, String password) throws Exception {
        var primary = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", username, "password", password)));
        var result = completeLogin(primary, null, Map.of());
        String raw = result.setCookie().stream().filter(v -> v.startsWith("DB_SESSION="))
                .map(v -> v.split("=")[1].split(";")[0]).findFirst().orElseThrow();
        return new Credentials(result.body().path("user").path("userId").asLong(),
                "DB_SESSION=" + raw, result.body().path("session").path("csrfToken").asText());
    }

    private HttpResult completeLogin(HttpResult primary, String cookie, Map<String, String> headers)
            throws Exception {
        assertThat(primary.status()).as("password login: %s", primary.body()).isEqualTo(202);
        assertThat(primary.setCookie()).isEmpty();
        assertThat(primary.body().has("session")).isFalse();
        String ref = primary.body().path("challenge").path("challengeId").asText();
        var result = send("POST", "/api/v1/auth/otp/verify", cookie, headers,
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", gateway.take(ref))));
        assertThat(result.status()).as("OTP verification: %s", result.body()).isEqualTo(200);
        return result;
    }

    private String statusOf(String rawSessionToken) {
        return jdbc.queryForObject(
                "SELECT status FROM banking.session WHERE session_token_hash = ?",
                String.class, HmacService.sha256Hex(rawSessionToken));
    }

    private void expireIdle(String rawSessionToken) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET last_activity_at = now() - interval '10 seconds', "
                        + "idle_expires_at = now() - interval '10 seconds' WHERE session_token_hash = ?",
                HmacService.sha256Hex(rawSessionToken)));
    }

    private static String problemCode(HttpResult result) {
        return result.body().path("code").asText();
    }

    private static void assertNoSecretLeak(HttpResult result, String sessionToken, String csrfToken) {
        String raw = result.body().toString();
        assertThat(raw).doesNotContain(sessionToken).doesNotContain(csrfToken);
    }

    // ------------------------------------------------------------------ ID-04 CSRF

    // CSRF-01: mọi unsafe method của phiên cookie thiếu X-CSRF-Token → 403, phiên còn nguyên.
    @Test
    void id04_csrf01_cookieMutationWithoutCsrfRejectedForAllUnsafeMethods() throws Exception {
        long userId = seedUser("id04c1", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c1", "S3curePassw0rd!");

        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            HttpResult result = send(method, "/api/v1/auth/logout", actor.cookie(), Map.of(), null);
            assertThat(result.status()).as("%s without CSRF", method).isEqualTo(403);
            assertThat(problemCode(result)).isEqualTo("AUTH_FORBIDDEN");
            assertNoSecretLeak(result, actor.cookie(), actor.csrf());
        }
        // GET (safe method) không yêu cầu CSRF → API-AUTH-07 đọc được.
        HttpResult read = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().path("user").path("userId").asLong()).isEqualTo(userId);
        assertThat(read.body().path("user").path("roles").toString()).contains("CUSTOMER");
    }

    // CSRF-02: proof sai → 403; phiên không bị revoke bởi thất bại CSRF.
    @Test
    void id04_csrf02_invalidCsrfProofRejectedSessionIntact() throws Exception {
        seedUser("id04c2", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c2", "S3curePassw0rd!");

        HttpResult wrong = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf() + "tampered"), null);

        assertThat(wrong.status()).isEqualTo(403);
        assertThat(problemCode(wrong)).isEqualTo("AUTH_FORBIDDEN");
        assertThat(statusOf(bearerTokenOf(actor))).isEqualTo("ACTIVE");
    }

    // CSRF-03: proof của phiên khác (cross-session) → 403; cả hai phiên không bị đụng tới.
    @Test
    void id04_csrf03_csrfProofFromAnotherSessionRejected() throws Exception {
        seedUser("id04c3a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04c3b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actorA = login("id04c3a", "S3curePassw0rd!");
        Credentials actorB = login("id04c3b", "S3curePassw0rd!");

        HttpResult cross = send("POST", "/api/v1/auth/logout", actorA.cookie(),
                Map.of("X-CSRF-Token", actorB.csrf()), null);

        assertThat(cross.status()).isEqualTo(403);
        assertThat(problemCode(cross)).isEqualTo("AUTH_FORBIDDEN");
        assertThat(statusOf(bearerTokenOf(actorA))).isEqualTo("ACTIVE");
        assertThat(statusOf(bearerTokenOf(actorB))).isEqualTo("ACTIVE");
    }

    // CSRF-04: hai giá trị X-CSRF-Token (transport lạ) → từ chối, không chọn "một trong hai".
    @Test
    void id04_csrf04_duplicateCsrfHeaderRejected() throws Exception {
        seedUser("id04c4", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c4", "S3curePassw0rd!");

        // Hai dòng X-CSRF-Token: client không được chọn "một trong hai" — transport lạ bị từ chối.
        HttpResult duplicated = sendMulti("POST", "/api/v1/auth/logout", actor.cookie(),
                List.of(new String[]{"X-CSRF-Token", actor.csrf()},
                        new String[]{"X-CSRF-Token", actor.csrf()}), null);

        assertThat(duplicated.status()).isEqualTo(403);
        assertThat(problemCode(duplicated)).isEqualTo("AUTH_FORBIDDEN");
    }

    // CSRF-05: phiên + proof hợp lệ → logout 204, phiên LOGGED_OUT, cookie clear, token chết.
    @Test
    void id04_csrf05_validCsrfLogoutRevokesSessionAndTokenDies() throws Exception {
        seedUser("id04c5", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c5", "S3curePassw0rd!");

        HttpResult logout = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()), null);

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie()).anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(statusOf(bearerTokenOf(actor))).isEqualTo("LOGGED_OUT");
        assertThat(logout.body().toString()).doesNotContain(actor.csrf());

        // Cookie cũ replay → 401 fail-closed, không fallback anonymous cho mutation.
        HttpResult reuse = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()), null);
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(problemCode(reuse)).isEqualTo("AUTH_SESSION_EXPIRED");
    }

    // CSRF-06: phiên hết hạn → proof từng hợp lệ cũng chết; không revival, không 403 CSRF.
    @Test
    void id04_csrf06_expiredSessionInvalidatesCsrfProofFailClosed() throws Exception {
        seedUser("id04c6", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c6", "S3curePassw0rd!");
        expireIdle(bearerTokenOf(actor));

        HttpResult result = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()), null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(problemCode(result)).isEqualTo("AUTH_SESSION_EXPIRED");
    }

    // CSRF-07: public login được bảo toàn; ambient cookie ACTIVE mà thiếu CSRF vẫn bị chặn.
    @Test
    void id04_csrf07_publicLoginPreservedActiveAmbientStillNeedsCsrf() throws Exception {
        seedUser("id04c7a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04c7b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c7a", "S3curePassw0rd!");

        HttpResult fresh = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "id04c7b", "password", "S3curePassw0rd!")));
        HttpResult freshSession = completeLogin(fresh, null, Map.of());
        assertThat(freshSession.body().path("user").path("userId").asLong())
                .isNotEqualTo(actor.userId());

        HttpResult ambientNoCsrf = send("POST", "/api/v1/auth/login", actor.cookie(), Map.of(),
                JSON.writeValueAsString(Map.of("username", "id04c7b", "password", "S3curePassw0rd!")));
        assertThat(ambientNoCsrf.status()).isEqualTo(403);
        assertThat(problemCode(ambientNoCsrf)).isEqualTo("AUTH_FORBIDDEN");

        HttpResult ambientWithCsrf = send("POST", "/api/v1/auth/login", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()),
                JSON.writeValueAsString(Map.of("username", "id04c7b", "password", "S3curePassw0rd!")));
        HttpResult ambientSession = completeLogin(ambientWithCsrf, actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()));
        assertThat(ambientSession.body().path("user").path("userId").asLong())
                .isEqualTo(freshSession.body().path("user").path("userId").asLong());
    }

    // CSRF-08: bearer-auth mutation KHÔNG bị áp yêu cầu CSRF cookie (07 §8.1.6; design/04 §4.3).
    @Test
    void id04_csrf08_bearerMutationDoesNotRequireCookieCsrf() throws Exception {
        seedUser("id04c8", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c8", "S3curePassw0rd!");

        HttpResult logout = send("POST", "/api/v1/auth/logout", null,
                Map.of("Authorization", "Bearer " + bearerTokenOf(actor)), null);

        assertThat(logout.status()).isEqualTo(204);
    }

    /** Token phiên = giá trị cookie DB_SESSION (opaque 32B Base64Url, design/04 §4.3). */
    private static String bearerTokenOf(Credentials actor) {
        return actor.cookie().substring("DB_SESSION=".length());
    }

    // CSRF-09: method-override không hạ được mức bảo vệ (POST vẫn là POST ⇒ vẫn cần CSRF).
    @Test
    void id04_csrf09_methodOverrideCannotBypassCsrf() throws Exception {
        seedUser("id04c9", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04c9", "S3curePassw0rd!");

        HttpResult headerOverride = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-HTTP-Method-Override", "GET"), null);
        assertThat(headerOverride.status()).isEqualTo(403);
        assertThat(problemCode(headerOverride)).isEqualTo("AUTH_FORBIDDEN");

        HttpResult paramOverride = send("POST", "/api/v1/auth/logout?_method=GET", actor.cookie(),
                Map.of(), null);
        assertThat(paramOverride.status()).isEqualTo(403);
        assertThat(problemCode(paramOverride)).isEqualTo("AUTH_FORBIDDEN");
    }

    // CSRF-10: rotation theo API-AUTH-07 — token cũ chết, token mới ràng buộc đúng phiên.
    @Test
    void id04_csrf10_sessionRotationInvalidatesOldProofOnlyForItsSession() throws Exception {
        seedUser("id04c10a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04c10b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actorA = login("id04c10a", "S3curePassw0rd!");
        Credentials actorB = login("id04c10b", "S3curePassw0rd!");

        HttpResult refreshA = send("GET", "/api/v1/auth/session", actorA.cookie(), Map.of(), null);
        assertThat(refreshA.status()).isEqualTo(200);
        String rotated = refreshA.body().path("session").path("csrfToken").asText();
        assertThat(rotated).isNotBlank().isNotEqualTo(actorA.csrf());

        // Token cũ của A giờ chết trên phiên A…
        HttpResult oldProof = send("POST", "/api/v1/auth/logout", actorA.cookie(),
                Map.of("X-CSRF-Token", actorA.csrf()), null);
        assertThat(oldProof.status()).isEqualTo(403);

        // …nhưng không ảnh hưởng phiên B (B vẫn dùng proof của mình được).
        HttpResult bStillValid = send("GET", "/api/v1/auth/session", actorB.cookie(), Map.of(), null);
        assertThat(bStillValid.status()).isEqualTo(200);

        // Token mới của A hoạt động.
        HttpResult withRotated = send("POST", "/api/v1/auth/logout", actorA.cookie(),
                Map.of("X-CSRF-Token", rotated), null);
        assertThat(withRotated.status()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ ID-04 RBAC

    // RBAC-01: role không có quyền → deny-by-default với Problem chuẩn.
    @Test
    void id04_rbac01_unauthorizedRoleDeniedOnProtectedOperations() throws Exception {
        seedUser("id04r1c", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04r1a", "S3curePassw0rd!", "STAFF", "AUDITOR");
        Credentials customer = login("id04r1c", "S3curePassw0rd!");
        Credentials auditor = login("id04r1a", "S3curePassw0rd!");

        HttpResult customerAdmin = send("GET", "/api/v1/admin/approvals", customer.cookie(), Map.of(), null);
        assertThat(customerAdmin.status()).isEqualTo(403);
        assertThat(problemCode(customerAdmin)).isEqualTo("AUTH_FORBIDDEN");

        HttpResult customerAudit = send("GET", "/api/v1/audit", customer.cookie(), Map.of(), null);
        assertThat(customerAudit.status()).isEqualTo(403);
        assertThat(problemCode(customerAudit)).isEqualTo("AUTH_FORBIDDEN");

        // AUDITOR có AUDIT_READ nhưng không có CUSTOMER_READ → /api/v1/admin/customers/* bị chặn.
        HttpResult auditorCustomers = send("GET", "/api/v1/admin/customers/1", auditor.cookie(), Map.of(), null);
        assertThat(auditorCustomers.status()).isEqualTo(403);
        assertThat(problemCode(auditorCustomers)).isEqualTo("AUTH_FORBIDDEN");
    }

    // RBAC-02: role được cấp vượt ranh giới bảo mật (403 không xảy ra; 404 là controller
    // chưa triển khai — RBAC chứng minh bằng tương phản 403 vs non-403 trên cùng endpoint).
    @Test
    void id04_rbac02_authorizedRolesPassTheSecurityBoundary() throws Exception {
        seedUser("id04r2ad", "S3curePassw0rd!", "STAFF", "ADMIN");
        seedUser("id04r2op", "S3curePassw0rd!", "STAFF", "OPERATIONS");
        Credentials admin = login("id04r2ad", "S3curePassw0rd!");
        Credentials operations = login("id04r2op", "S3curePassw0rd!");

        HttpResult adminApprovals = send("GET", "/api/v1/admin/approvals", admin.cookie(), Map.of(), null);
        assertThat(adminApprovals.status()).as("ADMIN passes approvals gate").isNotEqualTo(403);

        HttpResult operationsApprovals = send("GET", "/api/v1/admin/approvals", operations.cookie(), Map.of(), null);
        assertThat(operationsApprovals.status()).as("OPERATIONS passes approvals gate").isNotEqualTo(403);
    }

    // RBAC-03: role forge qua header/body không leo thang; unknown field bị từ chối 400.
    @Test
    void id04_rbac03_forgedRoleHeadersAndBodyCannotEscalate() throws Exception {
        seedUser("id04r3", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04r3", "S3curePassw0rd!");

        HttpResult forgedHeaders = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(
                "X-Roles", "ADMIN",
                "X-User-Id", "999999",
                "X-Actor-Type", "STAFF",
                "X-Privileges", "ROLE_MANAGE"), null);
        assertThat(forgedHeaders.status()).isEqualTo(200);
        assertThat(forgedHeaders.body().path("user").path("userId").asLong()).isEqualTo(actor.userId());
        assertThat(forgedHeaders.body().path("user").path("userType").asText()).isEqualTo("CUSTOMER");
        assertThat(forgedHeaders.body().path("user").path("roles").toString()).doesNotContain("ADMIN");
        assertThat(forgedHeaders.body().path("user").path("privileges").toString()).doesNotContain("ROLE_MANAGE");

        // Forge role trong JSON body của login: field lạ → 400 (fail-on-unknown-properties).
        HttpResult forgedBody = send("POST", "/api/v1/auth/login", null, Map.of(),
                "{\"username\":\"id04r3\",\"password\":\"S3curePassw0rd!\",\"roles\":[\"ADMIN\"]}");
        assertThat(forgedBody.status()).isEqualTo(400);
        assertThat(forgedBody.body().path("code").asText()).isIn("VALIDATION_ERROR", "MALFORMED_REQUEST");

        // Header forge không mở được gate staff: /api/v1/admin/approvals vẫn 403 cho CUSTOMER.
        HttpResult gate = send("GET", "/api/v1/admin/approvals", actor.cookie(), Map.of(
                "X-Roles", "ADMIN,OPERATIONS", "X-User-Id", "999999"), null);
        assertThat(gate.status()).isEqualTo(403);
        assertThat(problemCode(gate)).isEqualTo("AUTH_FORBIDDEN");
    }

    // RBAC-04: thu hồi role DB có hiệu lực ngay từ request kế (snapshot per-request,
    // không leo thang từ grant cũ); role thay đổi cũng không tự hồi sinh phiên đã chết.
    @Test
    void id04_rbac04_roleRevocationTakesEffectOnNextRequest() throws Exception {
        long userId = seedUser("id04r4", "S3curePassw0rd!", "STAFF", "OPERATIONS");
        Credentials actor = login("id04r4", "S3curePassw0rd!");

        HttpResult before = send("GET", "/api/v1/admin/approvals", actor.cookie(), Map.of(), null);
        assertThat(before.status()).as("OPERATIONS được cấp trước thu hồi").isNotEqualTo(403);

        tx.executeWithoutResult(ignored ->
                jdbc.update("DELETE FROM banking.user_role WHERE user_id = ?", userId));

        HttpResult after = send("GET", "/api/v1/admin/approvals", actor.cookie(), Map.of(), null);
        assertThat(after.status()).as("sau thu hồi → deny-by-default").isEqualTo(403);
        assertThat(problemCode(after)).isEqualTo("AUTH_FORBIDDEN");

        HttpResult view = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().path("user").path("roles").toString()).doesNotContain("OPERATIONS");
    }

    // ------------------------------------------------------------------ ID-04 Actor integrity

    // ACTOR-01: GET /auth/session chỉ trả actor từ snapshot phiên tin cậy; param forge bị bỏ qua.
    @Test
    void id04_actor01_sessionViewReturnsOnlyTrustedActor() throws Exception {
        long userId = seedUser("id04a1", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04a1", "S3curePassw0rd!");

        HttpResult plain = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        HttpResult forged = send("GET", "/api/v1/auth/session?userId=999999&customerId=42&userType=STAFF",
                actor.cookie(), Map.of("X-User-Id", "999999"), null);

        for (HttpResult result : List.of(plain, forged)) {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body().path("user").path("userId").asLong()).isEqualTo(userId);
            assertThat(result.body().path("user").path("userType").asText()).isEqualTo("CUSTOMER");
            assertThat(result.body().path("user").path("roles").toString()).contains("CUSTOMER");
            assertThat(result.body().path("user").path("customerId").isNull()).isTrue();
        }
        assertThat(plain.body().path("user").path("displayName").asText()).isEqualTo("id04a1");
    }

    // ACTOR-02: hai actor độc lập — context không trộn; B không bao giờ thấy dữ liệu của A.
    @Test
    void id04_actor02_actorContextIsolatedBetweenSessions() throws Exception {
        seedUser("id04a2a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04a2b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actorA = login("id04a2a", "S3curePassw0rd!");
        Credentials actorB = login("id04a2b", "S3curePassw0rd!");

        HttpResult viewA = send("GET", "/api/v1/auth/session", actorA.cookie(), Map.of(), null);
        HttpResult viewB = send("GET", "/api/v1/auth/session", actorB.cookie(), Map.of(), null);

        assertThat(viewA.body().path("user").path("userId").asLong()).isEqualTo(actorA.userId());
        assertThat(viewB.body().path("user").path("userId").asLong()).isEqualTo(actorB.userId());
        assertThat(viewB.body().path("user").path("userId").asLong()).isNotEqualTo(actorA.userId());
        assertThat(viewA.body().path("user").path("userId").asLong()).isNotEqualTo(actorB.userId());
    }

    // ACTOR-03: forged actor header không đổi được đối tượng logout — chỉ phiên của chủ token.
    @Test
    void id04_actor03_forgedActorIdCannotLogoutAnotherSession() throws Exception {
        seedUser("id04a3a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04a3b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actorA = login("id04a3a", "S3curePassw0rd!");
        Credentials actorB = login("id04a3b", "S3curePassw0rd!");

        HttpResult forgedLogout = send("POST", "/api/v1/auth/logout", actorA.cookie(),
                Map.of("X-CSRF-Token", actorA.csrf(), "X-User-Id", Long.toString(actorB.userId()),
                        "userId", Long.toString(actorB.userId())), null);

        assertThat(forgedLogout.status()).isEqualTo(204);
        assertThat(statusOf(bearerTokenOf(actorA))).isEqualTo("LOGGED_OUT");
        assertThat(statusOf(bearerTokenOf(actorB))).isEqualTo("ACTIVE");

        HttpResult bView = send("GET", "/api/v1/auth/session", actorB.cookie(), Map.of(), null);
        assertThat(bView.status()).isEqualTo(200);
        assertThat(bView.body().path("user").path("userId").asLong()).isEqualTo(actorB.userId());
    }

    // ------------------------------------------------------------------ ID-04 IDOR / object-level

    // IDOR-01: B không truy cập/sửa/xóa tài nguyên phiên của A — mọi thao tác theo token;
    // đổi query/body id không có endpoint nào nhận (identity API không nhận resource id của user khác).
    @Test
    void id04_idor01_crossActorResourceAccessDeniedWithoutLeak() throws Exception {
        seedUser("id04i1a", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        seedUser("id04i1b", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actorA = login("id04i1a", "S3curePassw0rd!");
        Credentials actorB = login("id04i1b", "S3curePassw0rd!");

        // B dùng proof của A trên phiên B → từ chối (không mượn được proof của object khác).
        HttpResult borrowedProof = send("POST", "/api/v1/auth/logout", actorB.cookie(),
                Map.of("X-CSRF-Token", actorA.csrf()), null);
        assertThat(borrowedProof.status()).isEqualTo(403);
        assertThat(problemCode(borrowedProof)).isEqualTo("AUTH_FORBIDDEN");
        assertThat(statusOf(bearerTokenOf(actorA))).isEqualTo("ACTIVE");
        assertThat(statusOf(bearerTokenOf(actorB))).isEqualTo("ACTIVE");

        // B không nhận được dữ liệu object của A trong response lỗi (chỉ Problem chung chung).
        assertThat(borrowedProof.body().has("user")).isFalse();
    }

    // IDOR-02: token hết hạn/thu hồi không cho phép thao tác object — revocation không bypass.
    @Test
    void id04_idor02_revokedSessionCannotBeUsedAgainstItsResource() throws Exception {
        seedUser("id04i2", "S3curePassw0rd!", "CUSTOMER", "CUSTOMER");
        Credentials actor = login("id04i2", "S3curePassw0rd!");

        HttpResult revoke = send("POST", "/api/v1/auth/logout", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()), null);
        assertThat(revoke.status()).isEqualTo(204);
        assertThat(statusOf(bearerTokenOf(actor))).isEqualTo("LOGGED_OUT");

        // Đọc /auth/session bằng token đã thu hồi → 401, không dữ liệu.
        HttpResult dead = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(dead.status()).isEqualTo(401);
        assertThat(problemCode(dead)).isEqualTo("AUTH_SESSION_EXPIRED");
        assertNoSecretLeak(dead, bearerTokenOf(actor), actor.csrf());

        // Absolute expiry — GET đọc lại không hồi sinh (giữ constraint absolute > created_at).
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET created_at = now() - interval '1 hour', "
                        + "absolute_expires_at = now() - interval '5 seconds' "
                        + "WHERE session_token_hash = ?", HmacService.sha256Hex(bearerTokenOf(actor))));
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.session SET status = 'ACTIVE', logged_out_at = NULL "
                        + "WHERE session_token_hash = ?", HmacService.sha256Hex(bearerTokenOf(actor))));
        HttpResult tampered = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(tampered.status()).isEqualTo(401);
        assertThat(problemCode(tampered)).isEqualTo("AUTH_SESSION_EXPIRED");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LoginGatewayConfiguration {
        @Bean
        LoginGateway securityRegressionLoginGateway() { return new LoginGateway(); }
    }

    /** Test-only capture, never an authentication bypass. */
    static final class LoginGateway implements OtpGateway {
        private final Map<String, char[]> codes = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return new OtpGateway.Result(Outcome.ACCEPTED, Failure.NONE);
        }

        String take(String ref) {
            char[] otp = codes.remove(ref);
            assertThat(otp).as("captured OTP for %s", ref).isNotNull();
            try {
                return new String(otp);
            } finally {
                java.util.Arrays.fill(otp, '\0');
            }
        }
    }
}
