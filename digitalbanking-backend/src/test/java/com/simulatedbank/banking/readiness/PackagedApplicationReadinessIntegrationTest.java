package com.simulatedbank.banking.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Local isolated packaged-demo checks only. No Cloud/TLS/identity acceptance is inferred. */
@Testcontainers
class PackagedApplicationReadinessIntegrationTest {
    private static final String APP_PASSWORD = "synthetic-app-" + java.util.UUID.randomUUID();
    private static final String MIGRATION_PASSWORD = "synthetic-migration-" + java.util.UUID.randomUUID();
    private static final String AES = key();
    private static final String HMAC = key();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path JAR = Path.of(System.getProperty("readiness.jar"));

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking").withUsername("postgres").withPassword("synthetic-admin-only")
            .withEnv("BANKING_APP_PASSWORD", APP_PASSWORD).withEnv("BANKING_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("docker/postgres/init/01-create-roles.sh")),
                    "/docker-entrypoint-initdb.d/01-create-roles.sh");

    @BeforeAll
    static void packagedArtifactExists() {
        assertThat(JAR).isRegularFile();
    }

    @Test
    void packagedDemoStartsWithSeparateDatabaseRolesAndRestartsSameArtifact() throws Exception {
        String digest = sha256();
        for (int run = 0; run < 2; run++) {
            try (var app = launch(Map.of())) {
                app.awaitHealthy();
                var health = app.request("/actuator/health", null, null, Map.of());
                assertThat(health.statusCode()).isEqualTo(200);
                JsonNode json = JSON.readTree(health.body());
                assertThat(json.path("status").asText()).isEqualTo("UP");
                assertThat(health.body()).doesNotContain("components", "details", "jdbc:", APP_PASSWORD, MIGRATION_PASSWORD);
                assertThat(health.headers().firstValue("X-Correlation-ID")).contains("readiness:packaged");
                assertThat(health.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(health.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
                for (String path : new String[]{"/api/v1/health", "/actuator/metrics", "/actuator/prometheus", "/v3/api-docs"}) {
                    var denied = app.request(path, null, null, Map.of());
                    assertThat(denied.statusCode()).isEqualTo(path.equals("/v3/api-docs") ? 404 : 401);
                    assertThat(denied.body()).doesNotContain("openapi", "jdbc:", APP_PASSWORD, MIGRATION_PASSWORD);
                }
                assertThat(app.request("/actuator/health", null, null,
                        Map.of("Origin", "https://unapproved.example.test")).statusCode()).isEqualTo(403);
                assertThat(app.request("/api/v1/auth/login", "x".repeat(65537), null, Map.of()).statusCode()).isEqualTo(413);
                assertThat(app.request("/actuator/health", null, "invalid trace", Map.of()).statusCode()).isEqualTo(400);
                var spoof = app.request("/actuator/health", null, null, Map.of("X-Forwarded-Proto", "https"));
                assertThat(spoof.headers().firstValue("Strict-Transport-Security")).isEmpty();
                app.stop();
                assertThat(app.process.exitValue()).isIn(0, 143);
                String output = app.output();
                assertSafe(output);
                assertThat(output).contains("\"environment\":\"demo\"", "HTTP_REQUEST_COMPLETED", "readiness:packaged");
                output.lines().filter(line -> line.contains("HTTP_REQUEST_COMPLETED")
                        && line.contains("readiness:packaged")).findFirst()
                        .ifPresent(line -> System.out.println("READINESS_LOG " + line));
                assertThat(sha256()).isEqualTo(digest);
                System.out.println("READINESS_PACKAGED run=" + (run + 1)
                        + " profile=demo health=UP anonymous_operational=DENIED cors=403 body_limit=413"
                        + " invalid_trace=400 forwarded_headers=IGNORED shutdown=OK jar_sha256=" + digest);
            }
        }
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_app", APP_PASSWORD);
             var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT current_user, rolsuper FROM pg_roles WHERE rolname=current_user");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("banking_app");
            assertThat(result.getBoolean(2)).isFalse();
        }
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", MIGRATION_PASSWORD);
             var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT count(*) FROM banking.flyway_schema_history WHERE version='1' AND success");
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void missingInvalidAndPublishedCryptoKeysFailPackagedStartupWithoutLeaking() throws Exception {
        for (Map<String, String> invalid : java.util.List.of(
                Map.of("APP_PII_AES_KEY_BASE64", ""),
                Map.of("APP_HMAC_KEY_BASE64", ""),
                Map.of("APP_PII_AES_KEY_BASE64", "SYNTHETIC_INVALID_KEY!"),
                Map.of("APP_PII_AES_KEY_BASE64", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="))) {
            assertFails(invalid, "crypto");
        }
    }

    @Test
    void wrongRuntimeOrMigrationCredentialsFailStartupWithoutLeaking() throws Exception {
        assertFails(Map.of("DB_APP_PASSWORD", ""), "missing-runtime-db");
        assertFails(Map.of("DB_MIGRATION_PASSWORD", ""), "missing-migration-db");
        assertFails(Map.of("DB_APP_PASSWORD", "SYNTHETIC_WRONG_RUNTIME_PASSWORD"), "runtime-db");
        assertFails(Map.of("DB_MIGRATION_PASSWORD", "SYNTHETIC_WRONG_MIGRATION_PASSWORD"), "migration-db");
    }

    @Test
    void missingOrWildcardCorsOriginFailsPackagedDemoStartup() throws Exception {
        assertFails(Map.of("APP_CORS_ALLOWED_ORIGINS", ""), "missing-cors");
        assertFails(Map.of("APP_CORS_ALLOWED_ORIGINS", "*"), "wildcard-cors");
    }

    private static void assertFails(Map<String, String> overrides, String scenario) throws Exception {
        try (var app = launch(overrides)) {
            assertThat(app.process.waitFor(45, TimeUnit.SECONDS)).as("Startup failure must terminate").isTrue();
            assertThat(app.process.exitValue()).isNotZero();
            assertSafe(app.output());
            for (String value : overrides.values()) {
                if (!value.isEmpty() && !value.equals("*")) assertThat(app.output()).doesNotContain(value);
            }
            System.out.println("READINESS_NEGATIVE scenario=" + scenario + " exit=" + app.process.exitValue() + " secrets=ABSENT");
        }
    }

    private static App launch(Map<String, String> overrides) throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        var log = Files.createTempFile("common-packaged-readiness-", ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", JAR.toString(), "--spring.profiles.active=demo", "--server.port=" + port);
        // Never inherit developer secrets, JAVA_TOOL_OPTIONS or SPRING_APPLICATION_JSON into the fixture.
        builder.environment().clear();
        Map<String, String> environment = new HashMap<>(Map.of(
                "DB_HOST", postgres.getHost(), "DB_PORT", postgres.getFirstMappedPort().toString(),
                "DB_NAME", "digital_banking", "DB_APP_USER", "banking_app", "DB_APP_PASSWORD", APP_PASSWORD,
                "DB_MIGRATION_USER", "banking_migrator", "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD,
                "APP_PII_AES_KEY_BASE64", AES, "APP_HMAC_KEY_BASE64", HMAC,
                "APP_CORS_ALLOWED_ORIGINS", "https://approved.example.test"));
        environment.put("APP_VERSION", "readiness-local-candidate");
        environment.put("DB_POOL_MIN_IDLE", "1");
        environment.put("DB_POOL_MAX_SIZE", "3");
        environment.putAll(overrides);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        try { return new App(builder.start(), port, log); }
        catch (Exception failure) { Files.deleteIfExists(log); throw failure; }
    }

    private static void assertSafe(String output) {
        assertThat(output).doesNotContain(APP_PASSWORD, MIGRATION_PASSWORD, AES, HMAC,
                "SYNTHETIC_WRONG_RUNTIME_PASSWORD", "SYNTHETIC_WRONG_MIGRATION_PASSWORD", "SYNTHETIC_INVALID_KEY!", "jdbc:postgresql:");
    }

    private static String key() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    private static String sha256() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(JAR)));
    }

    private static class App implements AutoCloseable {
        final Process process;
        final int port;
        final Path log;
        App(Process process, int port, Path log) { this.process=process; this.port=port; this.log=log; }
        HttpResponse<String> request(String path, String body, String correlation, Map<String, String> headers) throws Exception {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
                var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5)).header("X-Correlation-ID", correlation == null ? "readiness:packaged" : correlation);
                headers.forEach(builder::header);
                if (body == null) builder.GET();
                else builder.POST(HttpRequest.BodyPublishers.ofString(body));
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            }
        }
        void awaitHealthy() throws Exception {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        assertThat(process.isAlive()).as("Packaged application must stay running").isTrue();
                        try { return request("/actuator/health", null, null, Map.of()).statusCode() == 200; }
                        catch (java.io.IOException unavailable) { return false; }
                    });
        }
        String output() throws Exception { return Files.readString(log); }
        void stop() throws Exception {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
        @Override public void close() throws Exception {
            stop();
            Files.deleteIfExists(log);
        }
    }
}
