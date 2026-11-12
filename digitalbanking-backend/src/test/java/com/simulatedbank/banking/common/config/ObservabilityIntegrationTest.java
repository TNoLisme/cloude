package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
@Testcontainers
@Import({ObservabilityIntegrationTest.Harness.class, ObservabilityIntegrationTest.Fixture.class})
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityIntegrationTest {
    private static final String LEAK = "SYNTHETIC_SECRET_06";
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("observability").withUsername("postgres").withPassword("synthetic-test-only")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meters;

    private HttpResponse<String> request(String path, String token, String body, String correlation) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Correlation-ID", correlation).header("User-Agent", LEAK)
                    .header("Idempotency-Key", LEAK).header("X-CSRF-Token", LEAK);
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<JsonNode> completion(CapturedOutput output, String correlation) {
        return output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(line -> {
                    try { return mapper.readTree(line); }
                    catch (Exception failure) { throw new AssertionError("Invalid JSON log", failure); }
                }).filter(json -> json.path("event").asText().equals("HTTP_REQUEST_COMPLETED")
                        && json.path("correlation_id").asText().equals(correlation)).toList();
    }

    @Test @Order(1)
    void realLogsCoverSuccessValidationSecurityAndServerErrorWithoutLeaks(CapturedOutput output) throws Exception {
        String body = "{\"value\":\"" + LEAK + "\"}";
        for (var scenario : List.of(
                new Scenario("/api/v1/auth/login?password=" + LEAK, null, body, 200, "obs:success"),
                new Scenario("/api/v1/auth/login", null, "{\"value\":\"\"}", 400, "obs:validation"),
                new Scenario("/api/v1/accounts/" + LEAK, null, null, 401, "obs:security"),
                new Scenario("/api/v1/auth/passcode", null, body, 500, "obs:server"))) {
            var response = request(scenario.path, scenario.token, scenario.body, scenario.correlation);
            assertThat(response.statusCode()).isEqualTo(scenario.status);
            assertThat(response.headers().firstValue("X-Correlation-ID")).contains(scenario.correlation);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(completion(output, scenario.correlation)).hasSize(1));
            JsonNode log = completion(output, scenario.correlation).getFirst();
            assertThat(log.path("status").asInt()).isEqualTo(scenario.status);
            assertThat(log.path("timestamp").asText()).endsWith("Z");
            assertThat(log.path("service").asText()).isEqualTo("digital-banking-backend");
            assertThat(log.path("environment").asText()).isEqualTo("test");
            assertThat(log.path("request_id").asText()).matches("[0-9a-f-]{36}");
            assertThat(log.path("latency_ms").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(log.path("route").asText()).isEqualTo(scenario.status == 401 ? "UNMATCHED"
                    : scenario.status == 500 ? "/api/v1/auth/passcode" : "/api/v1/auth/login");
            if (scenario.status == 200) {
                assertThat(mapper.readTree(response.body()).path("requestId").asText())
                        .isEqualTo(log.path("request_id").asText());
            } else {
                assertThat(log.path("error_code").asText()).isNotBlank();
            }
        }
        String transfer = "{\"sourceAccount\":\"1234567890123456\",\"phone\":\"0987654321\","
                + "\"email\":\"alice@example.test\",\"otp\":\"812345\",\"description\":\"" + LEAK + "\"}";
        assertThat(request("/api/v1/transfers?key=" + LEAK, "transfer-customer", transfer,
                "obs:transfer-body").statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(completion(output, "obs:transfer-body")).hasSize(1));
        try (var client = HttpClient.newHttpClient()) {
            var cookie = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health"))
                    .header("Cookie", "DB_SESSION=" + LEAK).header("X-Correlation-ID", "obs:cookie").GET().build();
            assertThat(client.send(cookie, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        var logger = LoggerFactory.getLogger(Fixture.class);
        logger.atError().addKeyValue("password", LEAK).setCause(new IllegalStateException(LEAK + "\r\nFAKE_RECORD"))
                .log("password={} token={} phone=0987654321 email=alice@example.test", LEAK, LEAK);
        assertThat(output.getOut()).doesNotContain(LEAK, "0987654321", "alice@example.test", "FAKE_RECORD", "1234567890123456", "812345");
        assertThat(output.getOut().lines().filter(line -> line.startsWith("{")).map(line -> {
            try { return mapper.readTree(line); } catch (Exception failure) { throw new AssertionError(failure); }
        })).allSatisfy(json -> assertThat(json.has("timestamp")).isTrue());
    }

    @Test @Order(2)
    void invalidCorrelationAndSensitiveUnknownPathsNeverEnterLogs(CapturedOutput output) throws Exception {
        var response = request("/api/v1/" + LEAK, null, null, "invalid " + LEAK);
        assertThat(response.statusCode()).isEqualTo(400);
        String safe = response.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertThat(safe).matches("[0-9a-f-]{36}");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(completion(output, safe)).hasSize(1));
        assertThat(completion(output, safe).getFirst().path("route").asText()).isEqualTo("UNMATCHED");
        assertThat(output.getOut()).doesNotContain(LEAK);
    }

    @Test @Order(2)
    void asyncAndConcurrentRequestsKeepOneCompletionAndDistinctRequestIds(CapturedOutput output) throws Exception {
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> callAsync("obs:parallel-a"));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> callAsync("obs:parallel-b"));
        assertThat(first.get().statusCode()).isEqualTo(200);
        assertThat(second.get().statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(completion(output, "obs:parallel-a")).hasSize(1);
            assertThat(completion(output, "obs:parallel-b")).hasSize(1);
        });
        assertThat(completion(output, "obs:parallel-a").getFirst().path("request_id"))
                .isNotEqualTo(completion(output, "obs:parallel-b").getFirst().path("request_id"));
        assertThat(RequestContextHolder.current()).isEmpty();
    }

    private HttpResponse<String> callAsync(String correlation) {
        try { return request("/api/v1/fixture/async", "admin", null, correlation); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test @Order(3)
    void realHttpMetricsCountLatencyErrorsAndUseBoundedLabels(CapturedOutput output) throws Exception {
        long before = count("/api/v1/fixture/{id}", "200");
        for (int n = 0; n < 3; n++) {
            assertThat(request("/api/v1/fixture/" + LEAK + n + "?account=" + LEAK,
                    "admin", null, "obs:metric-" + n).statusCode()).isEqualTo(200);
        }
        request("/api/v1/auth/login", null, "{\"value\":\"\"}", "obs:metric-error");
        request("/api/v1/auth/passcode", null, "{}", "obs:metric-server");
        request("/api/v1/accounts", null, null, "obs:metric-security");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(count("/api/v1/fixture/{id}", "200")).isEqualTo(before + 3);
            assertThat(count("/api/v1/auth/login", "400")).isGreaterThan(0);
            assertThat(count("/api/v1/auth/passcode", "500")).isGreaterThan(0);
            assertThat(count("UNKNOWN", "401")).isGreaterThan(0);
        });
        var timer = meters.find("http.server.requests").tag("uri", "/api/v1/fixture/{id}").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);
        for (var meter : meters.find("http.server.requests").meters()) {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("method", "uri", "status", "outcome", "exception", "error");
                assertThat(tag.getValue()).doesNotContain(LEAK, "obs:", "sha256:");
            });
        }
        var export = request("/actuator/prometheus", "admin", null, "obs:export");
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.body()).contains("http_server_requests_seconds_count", "http_server_requests_seconds_bucket")
                .doesNotContain(LEAK, "obs:metric", "correlation_id", "request_id", "idempotency");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(completion(output, "obs:metric-0")).hasSize(1));
        assertThat(completion(output, "obs:metric-0").getFirst().path("user_id").asText())
                .matches("sha256:[0-9a-f]{64}");
    }

    private long count(String route, String status) {
        return meters.find("http.server.requests").tag("uri", route).tag("status", status).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    @Test @Order(4)
    void healthAndOperationalEndpointsEnforceRolesAndHideDetails() throws Exception {
        var probe = request("/actuator/health", null, null, "obs:probe-up");
        assertThat(probe.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(probe.body()).path("status").asText()).isEqualTo("UP");
        assertThat(probe.body()).doesNotContain("components", "details", "jdbc:");
        for (String path : List.of("/api/v1/health", "/actuator/metrics", "/actuator/prometheus")) {
            assertThat(request(path, null, null, "obs:anonymous").statusCode()).isEqualTo(401);
            assertThat(request(path, "customer", null, "obs:wrong-role").statusCode()).isEqualTo(403);
            assertThat(request(path, "admin", null, "obs:admin").statusCode()).isEqualTo(200);
            assertThat(request(path, "auditor", null, "obs:auditor").statusCode()).isEqualTo(200);
        }
        var health = mapper.readTree(request("/api/v1/health", "admin", null, "obs:health-up").body());
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.path("components").path("database").path("status").asText()).isEqualTo("UP");
        assertThat(health.path("checkedAt").asText()).endsWith("Z");
        assertThat(health.has("version")).isTrue();
        assertThat(health.toString()).doesNotContain("jdbc:", "password", "postgres", "error", "stackTrace");
    }

    @Test @Order(5)
    void stoppedPostgresReturnsSafe503HealthResponseAndPublicDownProbe(CapturedOutput output) throws Exception {
        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).exec();
        var probe = request("/actuator/health", null, null, "obs:probe-down");
        assertThat(probe.statusCode()).isEqualTo(503);
        assertThat(mapper.readTree(probe.body()).path("status").asText()).isEqualTo("DOWN");
        assertThat(probe.body()).doesNotContain("components", "details", "jdbc:");
        var response = request("/api/v1/health", "auditor", null, "obs:health-down");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        var health = mapper.readTree(response.body());
        assertThat(health.path("status").asText()).isEqualTo("DOWN");
        assertThat(health.path("components").path("database").path("status").asText()).isEqualTo("DOWN");
        assertThat(health.has("code")).isFalse();
        assertThat(health.toString()).doesNotContain("jdbc:", "password", "exception", "synthetic-test-only");
        assertThat(output.getOut()).doesNotContain("synthetic-test-only", "jdbc:postgresql:");
    }

    record Scenario(String path, String token, String body, int status, String correlation) {}

    @TestConfiguration
    static class Harness {
        @Bean SessionAuthenticator sessions() {
            return token -> switch (token) {
                case "admin" -> Optional.of(new SessionAuthenticator.Session("1", null, Set.of("ROLE_ADMIN"), "0".repeat(64)));
                case "auditor" -> Optional.of(new SessionAuthenticator.Session("2", null, Set.of("ROLE_AUDITOR"), "0".repeat(64)));
                case "transfer-customer" -> Optional.of(new SessionAuthenticator.Session("4", "4", Set.of("ROLE_CUSTOMER", "TRANSFER_CREATE"), "0".repeat(64)));
                case "customer" -> Optional.of(new SessionAuthenticator.Session("3", "4", Set.of("ROLE_CUSTOMER"), "0".repeat(64)));
                default -> Optional.empty();
            };
        }
    }

    @RestController
    static class Fixture {
        record Payload(@NotBlank String value) {}
        @PostMapping("/api/v1/auth/login")
        Map<String, String> login(@RequestBody @Valid Payload body) {
            return Map.of("requestId", RequestContextHolder.current().orElseThrow().requestId());
        }
        @PostMapping("/api/v1/transfers")
        Map<String, Boolean> transfer(@RequestBody String body) { return Map.of("synthetic", true); }
        @PostMapping("/api/v1/auth/passcode")
        void fail(@RequestBody String body) {
            LoggerFactory.getLogger(Fixture.class).error("credential=" + LEAK,
                    new IllegalStateException("token=" + LEAK + "\r\nFAKE_RECORD"));
            throw new IllegalArgumentException(body);
        }
        @GetMapping("/api/v1/fixture/{id}") Map<String, Boolean> template() { return Map.of("ok", true); }
        @GetMapping("/api/v1/fixture/async") Callable<Map<String, Boolean>> async() {
            return () -> Map.of("ok", true);
        }
    }
}
