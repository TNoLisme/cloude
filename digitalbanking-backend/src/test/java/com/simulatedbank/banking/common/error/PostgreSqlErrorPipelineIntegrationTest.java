package com.simulatedbank.banking.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;

/** DB primitives -> Spring translation -> production HTTP errors. Not a business-consumer test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import({PostgreSqlErrorPipelineIntegrationTest.Harness.class, PostgreSqlErrorPipelineIntegrationTest.Fixture.class})
class PostgreSqlErrorPipelineIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("common_quality_gate").withUsername("postgres").withPassword("synthetic-only")
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
    @Autowired Probe probe;

    @BeforeEach
    void prepareDisposableProbeState() {
        probe.transactions.executeWithoutResult(tx -> {
            probe.jdbc.execute("CREATE SCHEMA IF NOT EXISTS quality_gate");
            probe.jdbc.execute("CREATE TABLE IF NOT EXISTS quality_gate.probe "
                    + "(id integer PRIMARY KEY, value integer NOT NULL CHECK (value >= 0))");
            probe.jdbc.execute("TRUNCATE quality_gate.probe");
            probe.jdbc.update("INSERT INTO quality_gate.probe VALUES (1, 0), (2, 0), (3, 0)");
        });
        probe.attempts.set(0);
        probe.lastState.set(null);
        probe.captured.set(null);
    }

    private HttpResponse<String> request(String path, String credential, String correlation) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(15)).header("X-Correlation-ID", correlation);
            if (credential != null) builder.header("Authorization", "Bearer " + credential);
            return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> schemas() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("../digitalbanking-docs/openapi/digital-banking-v1.yaml")));
        return (Map<String, Map<String, Object>>) ((Map<String, Object>) spec.get("components")).get("schemas");
    }

    private JsonNode assertProblem(HttpResponse<String> response, ErrorCode expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expected.httpStatus());
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode json = mapper.readTree(response.body());
        for (Object field : (List<?>) schemas().get("Problem").get("required")) {
            assertThat(json.hasNonNull(field.toString())).as(field.toString()).isTrue();
        }
        assertThat(json.path("code").asText()).isEqualTo(expected.name());
        assertThat(json.path("status").asInt()).isEqualTo(expected.httpStatus());
        assertThat(json.path("retryable").asBoolean()).isEqualTo(expected.retryable());
        assertThat(json.path("detail").asText()).isEqualTo(expected.detail());
        assertThat(json.path("timestamp").asText()).endsWith("Z");
        Instant.parse(json.path("timestamp").asText());
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(json.path("correlationId").asText());
        assertThat(json.has("data")).isFalse();
        assertThat(response.body()).doesNotContain("quality_gate", "SELECT ", "INSERT ", "UPDATE ",
                "SQLException", "synthetic-only", "jdbc:postgresql", "stackTrace");
        if (expected == ErrorCode.SERVICE_UNAVAILABLE || expected == ErrorCode.IDEMPOTENCY_IN_PROGRESS) {
            long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
            assertThat(retry).isBetween(1L, 5L);
        }
        return json;
    }

    private void assertProbeFailure(String operation, String sqlState, ErrorCode code) throws Exception {
        int attempts = probe.attempts.get();
        var response = request("/api/v1/quality/database/" + operation, "quality-admin", "quality:" + operation);
        assertProblem(response, code);
        assertThat(probe.lastState.get()).isEqualTo(sqlState);
        assertThat(probe.attempts.get()).isEqualTo(attempts + 1); // no generic HTTP retry
        assertThat(probe.transactions.<Integer>execute(tx -> probe.jdbc.queryForObject(
                "SELECT value FROM quality_gate.probe WHERE id = 3", Integer.class))).isZero();
        assertThat(probe.transactions.<Integer>execute(tx -> probe.jdbc.queryForObject(
                "SELECT count(*) FROM quality_gate.probe", Integer.class))).isEqualTo(3);
        System.out.println("QUALITY_DB operation=" + operation + " sqlstate=" + sqlState
                + " code=" + code + " status=" + response.statusCode() + " rollback_marker=0 attempts=1");
    }

    @Test
    void realNowaitRequiresExplicitOwnerContextAndAuditClassificationWins() throws Exception {
        try (Connection holder = probe.dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                holder.createStatement().execute("SELECT id FROM quality_gate.probe WHERE id=1 FOR UPDATE");
                assertProbeFailure("nowait", "55P03", ErrorCode.SERVICE_UNAVAILABLE);
                assertProbeFailure("in-progress", "55P03", ErrorCode.IDEMPOTENCY_IN_PROGRESS);
                assertProbeFailure("audit-lock", "55P03", ErrorCode.AUDIT_WRITE_FAILED);
            } finally { holder.rollback(); }
        }
    }

    @Test
    void realLockTimeoutRollsBackBefore503WithoutHttpRetry() throws Exception {
        try (Connection holder = probe.dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                holder.createStatement().execute("SELECT id FROM quality_gate.probe WHERE id=1 FOR UPDATE");
                assertProbeFailure("lock-timeout", "55P03", ErrorCode.SERVICE_UNAVAILABLE);
            } finally { holder.rollback(); }
        }
    }

    @Test
    void realStatementTimeoutIsRetryable503AfterRollback() throws Exception {
        assertProbeFailure("statement-timeout", "57014", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void realUniqueAndCheckViolationsAreInternalUnlessOwnerClassifiesConflict() throws Exception {
        assertProbeFailure("unique", "23505", ErrorCode.INTERNAL_ERROR);
        assertProbeFailure("check", "23514", ErrorCode.INTERNAL_ERROR);
        assertProbeFailure("classified-conflict", "23505", ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void independentTransactionsProduceRealDeadlockAndMapVictimSafely() throws Exception {
        captureConcurrentFailure(false, "40P01");
        assertProbeFailure("captured", "40P01", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void independentSerializableTransactionsProduceReal40001AndMapSafely() throws Exception {
        captureConcurrentFailure(true, "40001");
        assertProbeFailure("captured", "40001", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void realClosedPostgresConnectionUses08003AndMapsTo503() throws Exception {
        Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        connection.close();
        try {
            connection.createStatement();
            throw new AssertionError("Closed PostgreSQL connection unexpectedly accepted a statement");
        } catch (SQLException failure) {
            assertThat(failure.getSQLState()).isEqualTo("08003");
            var translated = new org.springframework.jdbc.support.SQLStateSQLExceptionTranslator()
                    .translate("closed connection test", null, failure);
            assertThat(translated).isNotNull();
            probe.captured.set(translated);
        }
        assertProbeFailure("captured", "08003", ErrorCode.SERVICE_UNAVAILABLE);
    }

    private void captureConcurrentFailure(boolean serializable, String expectedState) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> collide(1, serializable, barrier));
            var second = executor.submit(() -> collide(2, serializable, barrier));
            var failures = java.util.stream.Stream.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(failures).hasSize(1);
            assertThat(Probe.sqlState(failures.getFirst())).isEqualTo(expectedState);
            probe.captured.set(failures.getFirst());
        }
    }

    private DataAccessException collide(int actor, boolean serializable, CyclicBarrier barrier) throws Exception {
        try (Connection connection = probe.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(serializable ? Connection.TRANSACTION_SERIALIZABLE : Connection.TRANSACTION_READ_COMMITTED);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                jdbc.execute("SET LOCAL statement_timeout = '10s'");
                if (serializable) {
                    jdbc.queryForObject("SELECT value FROM quality_gate.probe WHERE id=1", Integer.class);
                } else {
                    jdbc.update("UPDATE quality_gate.probe SET value=value+1 WHERE id=?", actor);
                }
                barrier.await(5, TimeUnit.SECONDS);
                jdbc.update("UPDATE quality_gate.probe SET value=value+1 WHERE id=?", serializable ? 1 : 3-actor);
                connection.commit();
                return null;
            } catch (DataAccessException failure) {
                return failure;
            } finally { connection.rollback(); }
        }
    }

    @Test
    void productionHealthAndPreMvcSecurityResponsesMatchAvailableOpenApiFields() throws Exception {
        var health = request("/api/v1/health", "quality-admin", "quality:health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode body = mapper.readTree(health.body());
        for (Object field : (List<?>) schemas().get("HealthResponse").get("required")) {
            assertThat(body.hasNonNull(field.toString())).as(field.toString()).isTrue();
        }
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("checkedAt").isTextual()).isTrue();
        Instant.parse(body.path("checkedAt").asText());
        assertThat(body.path("version").isTextual()).isTrue();
        assertThat(body.path("components").isObject()).isTrue();
        body.path("components").forEach(component -> {
            assertThat(component.path("status").asText()).isIn("UP", "DEGRADED", "DOWN");
            assertThat(component.size()).isEqualTo(1);
        });
        assertThat(body.has("data")).isFalse();
        assertProblem(request("/api/v1/health", null, "quality:unauthorized"), ErrorCode.AUTH_SESSION_EXPIRED);
        assertProblem(request("/api/v1/health", "quality-customer", "quality:forbidden"), ErrorCode.AUTH_FORBIDDEN);
        assertProblem(request("/api/v1/health", null, "invalid correlation"), ErrorCode.VALIDATION_ERROR);
        System.out.println("QUALITY_CONTRACT health=200 direct_HealthResponse anonymous=401 customer=403 invalid_header=400");
    }

    @TestConfiguration
    static class Harness {
        @Bean Probe databaseProbe(DataSource source) { return new Probe(source); }
        @Bean SessionAuthenticator qualitySessions() {
            return token -> switch (token) {
                case "quality-admin" -> Optional.of(new SessionAuthenticator.Session("101", null, Set.of("ROLE_ADMIN"), "0".repeat(64)));
                case "quality-customer" -> Optional.of(new SessionAuthenticator.Session("102", "103", Set.of("ROLE_CUSTOMER"), "0".repeat(64)));
                default -> Optional.empty();
            };
        }
    }

    @RestController
    static class Fixture {
        private final Probe probe;
        Fixture(Probe probe) { this.probe = probe; }
        @GetMapping("/api/v1/quality/database/{operation}")
        void databaseFailure(@PathVariable String operation) { probe.run(operation); }
    }

    static class Probe {
        final DataSource dataSource;
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<String> lastState = new AtomicReference<>();
        final AtomicReference<DataAccessException> captured = new AtomicReference<>();
        Probe(DataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbc = new JdbcTemplate(dataSource);
            this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        }
        void run(String operation) {
            attempts.incrementAndGet();
            try {
                transactions.executeWithoutResult(tx -> {
                    jdbc.update("UPDATE quality_gate.probe SET value=value+1 WHERE id=3");
                    switch (operation) {
                        case "nowait", "in-progress", "audit-lock" ->
                            jdbc.queryForList("SELECT id FROM quality_gate.probe WHERE id=1 FOR UPDATE NOWAIT");
                        case "lock-timeout" -> {
                            jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                            jdbc.queryForList("SELECT id FROM quality_gate.probe WHERE id=1 FOR UPDATE");
                        }
                        case "statement-timeout" -> {
                            jdbc.execute("SET LOCAL statement_timeout = '100ms'");
                            jdbc.execute("SELECT pg_sleep(2)");
                        }
                        case "unique", "classified-conflict" -> jdbc.update("INSERT INTO quality_gate.probe VALUES (1, 0)");
                        case "check" -> jdbc.update("UPDATE quality_gate.probe SET value=-1 WHERE id=1");
                        case "captured" -> throw captured.get();
                        default -> throw new IllegalArgumentException("Unknown test operation");
                    }
                });
            } catch (DataAccessException failure) {
                lastState.set(sqlState(failure));
                ErrorCode classified = switch (operation) {
                    case "in-progress" -> ErrorCode.IDEMPOTENCY_IN_PROGRESS;
                    case "audit-lock" -> ErrorCode.AUDIT_WRITE_FAILED;
                    case "classified-conflict" -> ErrorCode.IDEMPOTENCY_CONFLICT;
                    default -> null;
                };
                if (classified != null) throw new BankingBusinessException(classified, classified.detail(), Map.of(), failure);
                throw failure;
            }
        }
        static String sqlState(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) return sql.getSQLState();
            }
            return null;
        }
    }
}
