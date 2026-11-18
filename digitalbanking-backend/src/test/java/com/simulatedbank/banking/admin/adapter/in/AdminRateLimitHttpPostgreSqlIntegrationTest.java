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
import java.util.UUID;
import javax.sql.DataSource;
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
 * BE-ADM-API-01: the shared rate limiter applies to Admin writes as well (07 §4.3 `429 RATE_LIMITED`).
 * The bucket is per authenticated user, so a low limit proves the third request is rejected before the
 * controller while the first two still reach the Maker step-up gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
class AdminRateLimitHttpPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_rate");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "2");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    /** Only the OTP transport seam is a double; the limiter rejects before any challenge state matters. */
    @TestConfiguration
    static class OtpTransport {
        @Bean
        OtpGateway acceptingOtpGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) ->
                    new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
        }
    }

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    @Test
    void thirdAdminWriteWithinTheWindowIs429WithRetryAfter(CapturedOutput output,
            @Autowired JdbcTemplate jdbc) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        long admin = seedUser(tx, jdbc, "ADMIN");
        long target = seedUser(tx, jdbc, "OPERATIONS");
        String token = "adm-rate-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), admin));
        String body = "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                + "\",\"roleCode\":\"AUDITOR\"}";

        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                HttpResponse<String> allowed = http.send(request(body, token, "adm-rate-" + attempt).build(),
                        HttpResponse.BodyHandlers.ofString());
                // The Maker step-up gate answers 428: the request was not rejected by the rate limiter.
                assertThat(allowed.statusCode()).isEqualTo(428);
            }
            HttpResponse<String> limited = http.send(request(body, token, "adm-rate-3").build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(limited.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(limited.headers().firstValue("X-Correlation-ID")).contains("adm-rate-3");
            JsonNode problem = JSON.readTree(limited.body());
            assertThat(problem.get("code").asText()).isEqualTo("RATE_LIMITED");
            assertThat(problem.get("correlationId").asText()).isEqualTo("adm-rate-3");
            assertThat(limited.body()).doesNotContain(token, "banking.", "Exception");
            assertThat(output).doesNotContain(token);
        }
    }

    private HttpRequest.Builder request(String body, String token, String correlationId) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/roles"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", correlationId)
                .header("Idempotency-Key", "adm-rate-key-" + correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static long seedUser(TransactionTemplate tx, JdbcTemplate jdbc, String roleCode) {
        return tx.execute(ignored -> {
            String username = "adm-rate-" + UUID.randomUUID();
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            return userId;
        });
    }
}
