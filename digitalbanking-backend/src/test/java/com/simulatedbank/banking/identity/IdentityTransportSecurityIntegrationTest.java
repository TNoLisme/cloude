package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * BE-ID-TEST-03: bảo mật lớp vận chuyển phiên (transport/credential binding) và phần
 * CSRF/RBAC/mass-assignment chưa được phủ bởi
 * {@code IdentitySecurityIntegrationTest} / {@code IdentityAuthPostgreSqlIntegrationTest} /
 * {@code StepUpVerificationHttpIntegrationTest} / {@code DeviceMfaLoginHttpIntegrationTest}.
 *
 * Nguồn: design/04 §4.3 (cookie SESSION_TOKEN HttpOnly/Secure/SameSite=Strict; CSRF bắt buộc
 * cho cookie-auth unsafe), 07 §8.1 (API-AUTH-01..07), DEC-ID-002 (session.csrf_token_hash),
 * Modules contract §5.1 (SPI identity thật, không fallback giữa Bearer và cookie).
 * Mọi token/OTP chỉ dùng synthetic test account; không ghi secret vào evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(IdentityTransportSecurityIntegrationTest.Test03LoginGatewayConfiguration.class)
class IdentityTransportSecurityIntegrationTest {

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
        // TEST-03 chạy nhiều login/OTP trên cùng IP loopback; quota per-IP/username đã được
        // kiểm chứng riêng (LoginUsernameRateLimitHttpIntegrationTest, PublicOtpRateLimitTest)
        // nên ở đây nâng quota để nhiễu 429 không che khuất kịch bản bảo mật.
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "1000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "100");
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
    private Test03LoginGateway gateway;

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

    static final class Test03LoginGatewayConfiguration {
        @Bean
        Test03LoginGateway test03LoginGateway() {
            return new Test03LoginGateway();
        }
    }

    /** Test-only capture, never an authentication bypass. */
    static final class Test03LoginGateway implements OtpGateway {
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

    private record Credentials(long userId, String cookie, String csrf) {
        /** Token phiên = giá trị cookie DB_SESSION (opaque 32B Base64Url, design/04 §4.3). */
        String bearer() {
            return cookie.substring("DB_SESSION=".length());
        }
    }

    private record HttpResult(int status, JsonNode body, List<String> setCookie,
            Map<String, List<String>> headers) {
    }

    private HttpResult send(String method, String path, String cookieHeader, Map<String, String> headers,
            String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookieHeader != null) builder.header("Cookie", cookieHeader);
        headers.forEach(builder::header);
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isEmpty()
                ? JSON.nullNode() : JSON.readTree(response.body());
        return new HttpResult(response.statusCode(), body, response.headers().allValues("Set-Cookie"),
                response.headers().map());
    }

    /** Bỏ qua mật khẩu sai một lần có chủ ý bằng cách dùng mật khẩu đúng ngay từ đầu. */
    private Credentials login(String username, String password) throws Exception {
        var primary = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", username, "password", password)));
        assertThat(primary.status()).as("password login: %s", primary.body()).isEqualTo(202);
        assertThat(primary.setCookie()).as("202 chưa tạo session → không Set-Cookie").isEmpty();
        String ref = primary.body().path("challenge").path("challengeId").asText();
        var result = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", gateway.take(ref))));
        assertThat(result.status()).as("OTP verification: %s", result.body()).isEqualTo(200);
        String raw = result.setCookie().stream().filter(v -> v.startsWith("DB_SESSION="))
                .map(v -> v.split("=")[1].split(";")[0]).findFirst().orElseThrow();
        return new Credentials(result.body().path("user").path("userId").asLong(),
                "DB_SESSION=" + raw, result.body().path("session").path("csrfToken").asText());
    }

    private void seedUser(String username, String status) {
        // Hikari auto-commit=false trong project: mọi write seed phải nằm trong transaction
        // (cùng pattern seedUser của IdentitySecurityIntegrationTest).
        tx.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                            + "VALUES (?, ?, 'CUSTOMER', ?)",
                    username, hasher.hash("S3curePassw0rd!".toCharArray()), status);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT user_id, role_id FROM banking.app_user, banking.role "
                    + "WHERE username = ? AND role_code = 'CUSTOMER'", username);
        });
    }

    private String sessionStatus(String rawSessionToken) {
        return jdbc.queryForObject("SELECT status FROM banking.session WHERE session_token_hash = ?",
                String.class, HmacService.sha256Hex(rawSessionToken));
    }

    private long challengeAttemptCount(String challengeRef) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                Long.class, challengeRef);
    }

    private String challengeStatus(String challengeRef) {
        return jdbc.queryForObject(
                "SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                String.class, challengeRef);
    }

    private static String problemCode(HttpResult result) {
        return result.body().path("code").asText();
    }

    private static String randomForgedToken() {
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder sb = new StringBuilder(43);
        for (int i = 0; i < 43; i++) {
            sb.append(alphabet[ThreadLocalRandom.current().nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ TEST-03 TSEC cookie contract

    // TSEC-01: Set-Cookie DB_SESSION đúng contract design/04 §4.3 + 07 §8.1.1: HttpOnly,
    // SameSite=Strict, Path=/, Max-Age=28800; Secure chỉ bật qua APP_COOKIE_SECURE ở biên TLS.
    @Test
    void tsec01_sessionCookieAttributesMatchDesignContract() throws Exception {
        seedUser("t03c1", "ACTIVE");
        var primary = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c1", "password", "S3curePassw0rd!")));
        assertThat(primary.status()).isEqualTo(202);
        assertThat(primary.setCookie()).as("môi giai đoạn 202 chưa có phiên → không cookie").isEmpty();
        assertThat(primary.body().toString()).as("202 không chứa secret").doesNotContain("password");

        String ref = primary.body().path("challenge").path("challengeId").asText();
        var result = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", gateway.take(ref))));
        assertThat(result.status()).isEqualTo(200);
        String cookie = result.setCookie().stream().filter(v -> v.startsWith("DB_SESSION="))
                .findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/");
        assertThat(cookie).contains("Max-Age=28800");
        // Cấu hình mặc định test/dev: APP_COOKIE_SECURE chưa bật; cờ Secure được bật tại biên
        // HTTPS (design/04 §6.1). Assert đây là giá trị cấu hình, không phải lỗi cấu hình.
        assertThat(cookie).doesNotContain("Secure;");
        // Cookie chỉ chứa opaque token Base64Url, không chứa dữ liệu JWT/claims.
        String value = cookie.split("=")[1].split(";")[0];
        assertThat(value).matches("[A-Za-z0-9._~-]{40,}").doesNotContain(".");

        // GET /auth/session không cấp lại cookie (chỉ rotation CSRF trong body).
        var view = send("GET", "/api/v1/auth/session", "DB_SESSION=" + value, Map.of(), null);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.setCookie()).as("không reissue DB_SESSION khi GET").isEmpty();

        // Logout xả đúng cookie Max-Age=0 (t15 đã phủ 204; ở đây chỉ contract Set-Cookie).
        var csrf = view.body().path("session").path("csrfToken").asText();
        var logout = send("POST", "/api/v1/auth/logout", "DB_SESSION=" + value,
                Map.of("X-CSRF-Token", csrf), null);
        assertThat(logout.status()).isEqualTo(204);
        String clearCookie = logout.setCookie().stream().filter(v -> v.startsWith("DB_SESSION="))
                .findFirst().orElseThrow();
        assertThat(clearCookie).contains("Max-Age=0").contains("HttpOnly").contains("Path=/");
    }

    // TSEC-02: Authorization header + cookie DB_SESSION đồng thời → từ chối fail-closed,
    // không chọn "principal nào đó" và không hồi phục bằng cách rơi về một trong hai.
    @Test
    void tsec02_combinedCookieAndBearerCredentialsAreRejectedFailClosed() throws Exception {
        seedUser("t03c2", "ACTIVE");
        Credentials actor = login("t03c2", "S3curePassw0rd!");

        // Cùng token qua cả hai kênh vẫn là ambiguity → 401 (reject trước khi xác thực).
        var same = send("GET", "/api/v1/auth/session", actor.cookie(),
                Map.of("Authorization", "Bearer " + actor.bearer()), null);
        assertThat(same.status()).isEqualTo(401);
        assertThat(problemCode(same)).isEqualTo("AUTH_SESSION_EXPIRED");
        assertThat(sessionStatus(actor.bearer())).isEqualTo("ACTIVE");

        // Bearer A + cookie B: không được lấy principal từ kênh nào cả.
        Credentials other = login("t03c2", "S3curePassw0rd!");
        var mixed = send("GET", "/api/v1/auth/session",
                "DB_SESSION=" + other.bearer(),
                Map.of("Authorization", "Bearer " + actor.bearer()), null);
        assertThat(mixed.status()).isEqualTo(401);
        assertThat(problemCode(mixed)).isEqualTo("AUTH_SESSION_EXPIRED");
        assertThat(mixed.body().has("user")).isFalse();
        assertThat(sessionStatus(actor.bearer())).isEqualTo("ACTIVE");
        assertThat(sessionStatus(other.bearer())).isEqualTo("ACTIVE");
    }

    // TSEC-03: cookie giả mạo không thể gắn lên principal Bearer và ngược lại —
    // forged cookie + Bearer hợp lệ → 401; forged Bearer + cookie hợp lệ → 401.
    @Test
    void tsec03_forgedCredentialCannotRideOnValidOtherChannel() throws Exception {
        seedUser("t03c3", "ACTIVE");
        Credentials actor = login("t03c3", "S3curePassw0rd!");
        String forged = randomForgedToken();

        var forgedCookieWithValidBearer = send("GET", "/api/v1/auth/session",
                "DB_SESSION=" + forged, Map.of("Authorization", "Bearer " + actor.bearer()), null);
        assertThat(forgedCookieWithValidBearer.status()).isEqualTo(401);
        assertThat(problemCode(forgedCookieWithValidBearer)).isEqualTo("AUTH_SESSION_EXPIRED");
        assertThat(forgedCookieWithValidBearer.body().has("user")).isFalse();

        var forgedBearerWithValidCookie = send("GET", "/api/v1/auth/session", actor.cookie(),
                Map.of("Authorization", "Bearer " + forged), null);
        assertThat(forgedBearerWithValidCookie.status()).isEqualTo(401);
        assertThat(problemCode(forgedBearerWithValidCookie)).isEqualTo("AUTH_SESSION_EXPIRED");

        // Cookie giả mạo không được dùng như "session mới" cho mutation cần auth.
        var mutation = send("POST", "/api/v1/auth/logout", "DB_SESSION=" + forged,
                Map.of("X-CSRF-Token", actor.csrf()), null);
        assertThat(mutation.status()).isEqualTo(401);
        assertThat(sessionStatus(actor.bearer())).isEqualTo("ACTIVE");
    }

    // TSEC-04: hai cookie DB_SESSION hoặc hai Authorization header → ambiguity, từ chối;
    // không chosen-first-wins.
    @Test
    void tsec04_duplicateCredentialsRejectedWithoutChoosingOne() throws Exception {
        seedUser("t03c4", "ACTIVE");
        Credentials a = login("t03c4", "S3curePassw0rd!");
        Credentials b = login("t03c4", "S3curePassw0rd!");

        var twoCookies = send("GET", "/api/v1/auth/session",
                "DB_SESSION=" + a.bearer() + "; DB_SESSION=" + b.bearer(), Map.of(), null);
        assertThat(twoCookies.status()).isEqualTo(401);
        assertThat(problemCode(twoCookies)).isEqualTo("AUTH_SESSION_EXPIRED");

        HttpRequest.Builder twoAuth = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/session"))
                .header("Authorization", "Bearer " + a.bearer())
                .header("Authorization", "Bearer " + b.bearer())
                .GET();
        HttpResponse<String> response = http.send(twoAuth.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");

        assertThat(sessionStatus(a.bearer())).isEqualTo("ACTIVE");
        assertThat(sessionStatus(b.bearer())).isEqualTo("ACTIVE");
    }

    // TSEC-05: scheme Authorization khác Bearer, hoặc Bearer rỗng/ký tự cấm → từ chối,
    // không fallback xuống ambient cookie.
    @Test
    void tsec05_nonBearerOrMalformedAuthorizationRejectedNoCookieFallback() throws Exception {
        seedUser("t03c5", "ACTIVE");
        Credentials actor = login("t03c5", "S3curePassw0rd!");

        for (String value : new String[]{"Basic " + actor.bearer(), "Bearer ", "Bearer x y",
                "bearer  ", "Token " + actor.bearer()}) {
            var result = send("GET", "/api/v1/auth/session", actor.cookie(),
                    Map.of("Authorization", value), null);
            assertThat(result.status()).as("Authorization '%s'", value).isEqualTo(401);
            assertThat(problemCode(result)).isEqualTo("AUTH_SESSION_EXPIRED");
        }
        assertThat(sessionStatus(actor.bearer())).isEqualTo("ACTIVE");

        // Cookie hợp lệ vẫn hoạt động bình thường sau các lần từ chối trên.
        var ok = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(ok.status()).isEqualTo(200);
    }

    // TSEC-06: Bearer-auth là explicit credential → CSRF không áp dụng cho kênh này
    // (07 §8.1.6: cookie + CSRF header "nếu dùng cookie"); proof CSRF sai/bịa không chặn
    // mutation Bearer, chứng minh không có downgrade cookie→Bearer.
    @Test
    void tsec06_bearerMutationDoesNotRequireOrVerifyCsrfProof() throws Exception {
        seedUser("t03c6", "ACTIVE");
        Credentials actor = login("t03c6", "S3curePassw0rd!");

        var tampered = send("POST", "/api/v1/auth/logout", null,
                Map.of("Authorization", "Bearer " + actor.bearer(),
                        "X-CSRF-Token", actor.csrf() + "tampered"), null);
        assertThat(tampered.status()).as("Bearer logout bỏ qua CSRF proof").isEqualTo(204);
        assertThat(sessionStatus(actor.bearer())).isEqualTo("LOGGED_OUT");

        // CSRF proof của user khác cũng không được dùng để "trông như có proof" trên kênh cookie.
        Credentials survivor = login("t03c6", "S3curePassw0rd!");
        Credentials victim = login("t03c6", "S3curePassw0rd!");
        var borrowed = send("POST", "/api/v1/auth/logout", victim.cookie(),
                Map.of("X-CSRF-Token", survivor.csrf()), null);
        assertThat(borrowed.status()).isEqualTo(403);
        assertThat(problemCode(borrowed)).isEqualTo("AUTH_FORBIDDEN");
        assertThat(sessionStatus(victim.bearer())).isEqualTo("ACTIVE");
        assertThat(sessionStatus(survivor.bearer())).isEqualTo("ACTIVE");
    }

    // TSEC-07: anonymous trên các endpoint bảo vệ → 401 AUTH_SESSION_EXPIRED fail-closed,
    // không 403/404/500; health vẫn public; public login vẫn hoạt động không cookie.
    @Test
    void tsec07_anonymousRequestsToProtectedEndpointsAreFailClosed() throws Exception {
        seedUser("t03c7", "ACTIVE");

        for (String[] probe : new String[][]{
                {"GET", "/api/v1/auth/session"},
                {"POST", "/api/v1/auth/logout"},
                {"GET", "/api/v1/transfers"},
                {"GET", "/api/v1/admin/approvals"},
                {"GET", "/api/v1/admin/customers/1"},
                {"GET", "/api/v1/audit"}}) {
            var result = send(probe[0], probe[1], null, Map.of(), null);
            assertThat(result.status()).as("%s %s", probe[0], probe[1]).isEqualTo(401);
            assertThat(problemCode(result)).isEqualTo("AUTH_SESSION_EXPIRED");
            assertThat(result.body().has("user")).isFalse();
        }

        var health = send("GET", "/actuator/health", null, Map.of(), null);
        assertThat(health.status()).isEqualTo(200);

        var publicLogin = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c7", "password", "S3curePassw0rd!")));
        assertThat(publicLogin.status()).isEqualTo(202);
        assertThat(publicLogin.body().has("session")).isFalse();
    }

    // TSEC-08: user DISABLED đăng nhập HTTP → 403 AUTH_FORBIDDEN, không challenge, không
    // session; LOCKED (locked_until tương lai) → 423 AUTH_ACCOUNT_LOCKED; cùng pattern 07 §8.1.1.
    @Test
    void tsec08_disabledUserForbiddenLockedUser423WithoutSessionOrChallenge() throws Exception {
        seedUser("t03c8d", "DISABLED");
        seedUser("t03c8l", "LOCKED");
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.app_user SET locked_until = now() + interval '10 minutes' "
                        + "WHERE username = 't03c8l'"));

        var disabled = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c8d", "password", "S3curePassw0rd!")));
        assertThat(disabled.status()).isEqualTo(403);
        assertThat(problemCode(disabled)).isEqualTo("AUTH_FORBIDDEN");
        assertThat(disabled.body().has("challenge")).isFalse();
        assertThat(disabled.setCookie()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_challenge c JOIN banking.app_user u "
                        + "ON u.user_id = c.user_id WHERE u.username = 't03c8d'", Long.class)).isZero();

        var locked = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c8l", "password", "S3curePassw0rd!")));
        assertThat(locked.status()).isEqualTo(423);
        assertThat(problemCode(locked)).isEqualTo("AUTH_ACCOUNT_LOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.session s JOIN banking.app_user u "
                        + "ON u.user_id = s.user_id WHERE u.username IN ('t03c8d','t03c8l')",
                Long.class)).isZero();
    }

    // TSEC-09: mass assignment trên proof endpoint — field đặc quyền lạ trong JSON → 400
    // (fail-on-unknown-properties), challenge KHÔNG bị mutate; proof gốc vẫn verify được.
    @Test
    void tsec09_massAssignmentFieldsRejectedAndChallengeUntouched() throws Exception {
        seedUser("t03c9", "ACTIVE");
        var primary = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c9", "password", "S3curePassw0rd!")));
        String ref = primary.body().path("challenge").path("challengeId").asText();
        String correctOtp = gateway.take(ref);

        var injected = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                "{\"challengeId\":\"" + ref + "\",\"otp\":\"" + correctOtp
                        + "\",\"roles\":[\"ADMIN\"],\"userType\":\"STAFF\",\"userId\":999999,"
                        + "\"privileges\":[\"ROLE_MANAGE\"],\"customerId\":42}");
        assertThat(injected.status()).as("%s", injected.body()).isEqualTo(400);
        assertThat(injected.body().path("code").asText()).isIn("VALIDATION_ERROR", "MALFORMED_REQUEST");

        // Challenge không mutate: status SENT, attempt_count không tăng.
        assertThat(challengeStatus(ref)).isEqualTo("SENT");
        assertThat(challengeAttemptCount(ref)).isZero();

        // Proof đúng gốc vẫn dùng được → mass assignment không tiêu thụ/chiếm challenge.
        var legit = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", correctOtp)));
        assertThat(legit.status()).isEqualTo(200);
        assertThat(legit.body().path("user").path("userType").asText()).isEqualTo("CUSTOMER");
        assertThat(legit.body().path("user").path("roles").toString()).doesNotContain("ADMIN");

        // Mass assignment trên step-up verify (kênh authenticated): 400, không mutate.
        Credentials actor = login("t03c9", "S3curePassw0rd!");
        var stepUpInjected = send("POST", "/api/v1/auth/step-up/verify", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()),
                "{\"challengeId\":\"" + ref + "\",\"method\":\"OTP\",\"otp\":\"123456\","
                        + "\"roles\":[\"ADMIN\"],\"userId\":1}");
        assertThat(stepUpInjected.status()).isEqualTo(400);
        assertThat(stepUpInjected.body().path("code").asText()).isIn("VALIDATION_ERROR",
                "MALFORMED_REQUEST");
        // Challenge đã được consume bởi verify hợp lệ trước đó — không được hồi sinh/consume lần hai.
        var replay = send("POST", "/api/v1/auth/step-up/verify", actor.cookie(),
                Map.of("X-CSRF-Token", actor.csrf()),
                JSON.writeValueAsString(Map.of("challengeId", ref, "method", "OTP", "otp", correctOtp)));
        assertThat(replay.status()).isIn(404, 409, 410);
    }

    // TSEC-10: RBAC deny-by-default ma trận customer trên toàn bộ surface quản trị; contract
    // nhất quán 403 AUTH_FORBIDDEN với Problem chuẩn (RBAC-01/02 đã phủ contrast role được cấp).
    @Test
    void tsec10_customerTokenDeniedAcrossAdministrativeSurface() throws Exception {
        seedUser("t03c10", "ACTIVE");
        Credentials actor = login("t03c10", "S3curePassw0rd!");

        // Chỉ probe các path đã được rule authorizeHttpRequests phủ — 403 ở đây là RBAC
        // (đã kèm CSRF proof cho mutation, nên 403 không thể là CSRF).
        for (String[] probe : new String[][]{
                {"GET", "/api/v1/admin/customers/1", null},
                {"GET", "/api/v1/admin/customers/1/accounts", null},
                {"GET", "/api/v1/admin/approvals", null},
                {"GET", "/api/v1/admin/approvals/1", null},
                {"GET", "/api/v1/audit", null},
                {"GET", "/api/v1/audit/export", null},
                {"GET", "/api/v1/fraud-flags", null},
                {"POST", "/api/v1/fraud-flags/1/review", "{}"},
                {"POST", "/api/v1/admin/limits", "{}"},
                {"POST", "/api/v1/admin/limits/1/approve", "{}"},
                {"POST", "/api/v1/admin/roles", "{}"},
                {"POST", "/api/v1/admin/roles/1/approve", "{}"}}) {
            var result = send(probe[0], probe[1], actor.cookie(),
                    Map.of("X-CSRF-Token", actor.csrf()), probe[2]);
            assertThat(result.status()).as("%s %s", probe[0], probe[1]).isEqualTo(403);
            assertThat(problemCode(result)).isEqualTo("AUTH_FORBIDDEN");
        }
    }

    // TSEC-11: GET /auth/session không rò raw session token/hash trong body (CSRF token thô
    // trả hợp lệ theo API-AUTH-07; token phiên và SHA-256 hash DB không được xuất hiện).
    @Test
    void tsec11_sessionViewNeverExposesSessionTokenOrHash() throws Exception {
        seedUser("t03c11", "ACTIVE");
        Credentials actor = login("t03c11", "S3curePassw0rd!");

        var view = send("GET", "/api/v1/auth/session", actor.cookie(), Map.of(), null);
        assertThat(view.status()).isEqualTo(200);
        String body = view.body().toString();
        assertThat(body).doesNotContain(actor.bearer());
        assertThat(body).doesNotContain(HmacService.sha256Hex(actor.bearer()));
        assertThat(body).doesNotContain("password");
        assertThat(view.body().path("session").path("csrfToken").asText())
                .isNotEqualTo(HmacService.sha256Hex(actor.csrf()));
    }

    // ------------------------------------------------------------------ TEST-03 OTP log/response leakage

    // TSEC-12: OTP không bao giờ xuất hiện trong bất kỳ response nào của flow login/verify
    // (202 challenge, 200 auth, 401 sai OTP); body không có field otp/otpHash.
    @Test
    void tsec12_otpNeverAppearsInResponsesOrChallengePayload() throws Exception {
        seedUser("t03c12", "ACTIVE");
        var primary = send("POST", "/api/v1/auth/login", null, Map.of(),
                JSON.writeValueAsString(Map.of("username", "t03c12", "password", "S3curePassw0rd!")));
        assertThat(primary.status()).isEqualTo(202);
        String ref = primary.body().path("challenge").path("challengeId").asText();
        String challengeBody = primary.body().toString();
        assertThat(challengeBody).doesNotContain("\"otp\"").doesNotContain("otpHash");
        // maskedDestination luôn che dấu, không phải plaintext destination.
        String masked = primary.body().path("challenge").path("maskedDestination").asText("");
        if (!masked.isEmpty()) {
            assertThat(masked).contains("*");
        }

        String otp = gateway.take(ref);
        var wrong = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", "000000")));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().toString()).doesNotContain(otp);

        var ok = send("POST", "/api/v1/auth/otp/verify", null, Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", otp)));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().toString()).doesNotContain(otp);
        // OTP đã hết giá trị; token phiên không nằm cạnh OTP trong bất kỳ response nào.
        String rawSession = ok.setCookie().stream().filter(v -> v.startsWith("DB_SESSION="))
                .map(v -> v.split("=")[1].split(";")[0]).findFirst().orElseThrow();
        assertThat(challengeBody).doesNotContain(rawSession);
    }
}
