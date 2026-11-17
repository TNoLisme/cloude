package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.Map;
import javax.sql.DataSource;
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
 * T21: DB không khả dụng → HTTP 503 SERVICE_UNAVAILABLE, fail-closed (không cấp session).
 * Class riêng với container có chủ ý (stop giữa test, không restart lại cho test khác —
 * connection info của context không cập nhật được sau restart).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(IdentityAuthDbFailureIntegrationTest.LoginGatewayConfiguration.class)
class IdentityAuthDbFailureIntegrationTest {

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
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1500");
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

    // T21: DB chết → 503, không credential nào được cấp; response không lộ DB internals.
    // ID-03-T16: bảo mật fail-closed cả hai chiều — login (cấp phiên) VÀ xác thực phiên hiện có
    // (protected endpoint) đều 503 SERVICE_UNAVAILABLE, không fallback anonymous/grant.
    @Test
    void t21_databaseFailureReturnsServiceUnavailableFailClosed() throws Exception {
        var tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        tx.executeWithoutResult(status -> new JdbcTemplate(dataSource).update(
                "INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                        + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')",
                "t21user", hasher.hash("DbFailPass1".toCharArray())));
        HttpClient http = HttpClient.newHttpClient();

        HttpRequest ok = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"t21user\",\"password\":\"DbFailPass1\"}"))
                .build();
        HttpResponse<String> primary = http.send(ok, HttpResponse.BodyHandlers.ofString());
        assertThat(primary.statusCode()).isEqualTo(202);
        assertThat(primary.headers().allValues("Set-Cookie")).isEmpty();
        String ref = JSON.readTree(primary.body()).path("challenge").path("challengeId").asText();
        HttpRequest verify = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/v1/auth/otp/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("challengeId", ref, "otp", gateway.take(ref)))))
                .build();
        HttpResponse<String> granted = http.send(verify, HttpResponse.BodyHandlers.ofString());
        assertThat(granted.statusCode()).isEqualTo(200);
        String sessionToken = granted.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("DB_SESSION="))
                .map(cookie -> cookie.split("=")[1].split(";")[0])
                .findFirst()
                .orElseThrow();

        // DB ngừng khả dụng (fail-closed phải giữ 503 — không login fallback nào).
        postgres.stop();
        HttpResponse<String> failure = http.send(ok, HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(failure.body());
        assertThat(failure.statusCode()).isEqualTo(503);
        assertThat(body.path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.toString()).doesNotContain("t21user").doesNotContain("password");

        // ID-03-T16: xác thực phiên đã cấp trước đó qua SPI thật → 503 (không grant, không anonymous).
        HttpRequest bearerLogout = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
                .header("Authorization", "Bearer " + sessionToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> authFailure = http.send(bearerLogout, HttpResponse.BodyHandlers.ofString());
        JsonNode authBody = JSON.readTree(authFailure.body());
        assertThat(authFailure.statusCode()).isEqualTo(503);
        assertThat(authBody.path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(authBody.toString()).doesNotContain(sessionToken).doesNotContain("session_token_hash");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LoginGatewayConfiguration {
        @Bean
        LoginGateway dbFailureRegressionLoginGateway() { return new LoginGateway(); }
    }

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
