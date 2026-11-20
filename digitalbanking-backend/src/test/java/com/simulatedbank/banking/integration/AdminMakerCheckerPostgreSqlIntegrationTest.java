package com.simulatedbank.banking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-INT-09: real HTTP, banking_app, canonical migrations and all production providers; only SMS is simulated. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AdminMakerCheckerPostgreSqlIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAKER = 91001;
    private static final long CHECKER = 91002;
    private static final long OTHER_CHECKER = 91003;
    private static final String APPROVE = "{\"decision\":\"APPROVE\"}";
    private static final LinkedBlockingQueue<String> CODES = new LinkedBlockingQueue<>();
    private static final HttpServer SMS = smsBoundary();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql")
            .withCommand("postgres", "-c", "log_error_verbosity=verbose", "-c", "log_parameter_max_length_on_error=0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
        registry.add("app.notification.sms.enabled", () -> true);
        registry.add("app.notification.sms.endpoint", () -> "http://127.0.0.1:" + SMS.getAddress().getPort() + "/sms");
        for (long user : List.of(MAKER, CHECKER, OTHER_CHECKER)) {
            registry.add("app.notification.sms.demo-staff-contacts[" + user + "]", () -> "0900000301");
        }
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> 10000);
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> 10000);
        registry.add("app.admin.ops.enabled", () -> false);
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @LocalServerPort int port;
    @Autowired DataSource source;
    private JdbcTemplate db;
    private long target;
    private long customer;
    private String targetToken;
    private String makerToken;
    private String checkerToken;
    private String otherToken;

    @BeforeEach
    void fixture() {
        db = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(new JdbcTemplate(source).queryForObject("SELECT current_user", String.class)).isEqualTo("banking_app");
        assertThat(db.queryForObject("SELECT max(version::int) FROM banking.flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(23);
        for (long user : List.of(MAKER, CHECKER, OTHER_CHECKER)) {
            db.update("INSERT INTO banking.app_user(user_id,username,password_hash,user_type) OVERRIDING SYSTEM VALUE "
                    + "VALUES (?,?,'synthetic','STAFF') ON CONFLICT DO NOTHING", user, "int09-staff-" + user);
            db.update("INSERT INTO banking.user_role(user_id,role_id) SELECT ?,role_id FROM banking.role "
                    + "WHERE role_code='ADMIN' ON CONFLICT DO NOTHING", user);
            db.update("INSERT INTO banking.user_privilege(user_id,privilege_code,status) "
                    + "VALUES (?,'CHECKER','ACTIVE') ON CONFLICT DO NOTHING", user);
        }
        target = db.queryForObject("INSERT INTO banking.app_user(username,password_hash,user_type) "
                + "VALUES (?,'synthetic','STAFF') RETURNING user_id", Long.class, "int09-" + UUID.randomUUID());
        long owner = db.queryForObject("INSERT INTO banking.app_user(username,password_hash,user_type) "
                + "VALUES (?,'synthetic','CUSTOMER') RETURNING user_id", Long.class, "int09-" + UUID.randomUUID());
        customer = db.queryForObject("INSERT INTO banking.customer(user_id,full_name_enc) VALUES (?,?) RETURNING customer_id",
                Long.class, owner, new byte[] {1, 2, 3});
        targetToken = session(target);
        makerToken = session(MAKER);
        checkerToken = session(CHECKER);
        otherToken = session(OTHER_CHECKER);
        CODES.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "limits"})
    void replayAndMakerSeparation(String type) throws Exception {
        String path = path(type);
        String body = proposal(type);
        String key = key();
        String proof = proof(path, makerToken, body, key);
        HttpResponse<String> created = call(path, makerToken, body, key, proof);
        assertStatus(created, 201);
        long id = id(created);
        assertThat(JSON.readTree(call(path, makerToken, body, key, null).body())).isEqualTo(JSON.readTree(created.body()));
        assertProblem(call(path, makerToken, changed(body), key, null), 409, "IDEMPOTENCY_CONFLICT");
        assertProblem(call(decisionPath(type, id), makerToken, APPROVE, key(), null), 409, "ROLE_CONFLICT");
        assertPending(type, id);
        String decisionKey = key();
        String decisionProof = proof(decisionPath(type, id), checkerToken, APPROVE, decisionKey);
        HttpResponse<String> applied = call(decisionPath(type, id), checkerToken, APPROVE, decisionKey, decisionProof);
        assertStatus(applied, 200);
        HttpResponse<String> replay = call(decisionPath(type, id), checkerToken, APPROVE, decisionKey, null);
        assertStatus(replay, 200);
        assertThat(JSON.readTree(replay.body())).isEqualTo(JSON.readTree(applied.body()));
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertApplied(type, id);
        assertThat(audits(id, type, "REQUESTED")).isEqualTo(1);
        assertThat(audits(id, type, "APPROVED")).isEqualTo(1);
        assertProblem(call(decisionPath(type, id), checkerToken, APPROVE, key(), null),
                409, "APPROVAL_ALREADY_DECIDED");
    }

    @ParameterizedTest
    @CsvSource({"roles,key", "roles,payload", "limits,key", "limits,payload"})
    void makerProofCannotAuthorizeAnotherProposal(String type, String mutation) throws Exception {
        String key = key();
        String body = proposal(type);
        String proof = proof(path(type), makerToken, body, key);
        assertProblem(call(path(type), makerToken, mutation.equals("payload") ? changed(body) : body,
                mutation.equals("key") ? key() : key, proof), 409, "STEP_UP_CHALLENGE_MISMATCH");
        assertUnconsumed(proof);
        assertThat(count("SELECT count(*) FROM banking.approval_request WHERE maker_id=? AND target_id=?",
                MAKER, Long.toString(type.equals("roles") ? target : customer))).isZero();
        assertStatus(call(path(type), makerToken, body, key, proof), 201);
    }

    @ParameterizedTest
    @CsvSource({"roles,note", "roles,decision", "roles,approval", "roles,actor", "roles,payload",
            "limits,note", "limits,decision", "limits,approval", "limits,actor", "limits,payload"})
    void checkerProofIsBoundToExactDecision(String type, String mutation) throws Exception {
        long id = propose(type);
        String proof = proof(decisionPath(type, id), checkerToken, APPROVE, key());
        long destination = mutation.equals("approval") ? propose(type) : id;
        String decision = mutation.equals("decision") ? "{\"decision\":\"REJECT\"}"
                : mutation.equals("note") ? "{\"decision\":\"APPROVE\",\"decisionNote\":\"changed\"}" : APPROVE;
        if (mutation.equals("payload")) {
            db.update("UPDATE banking.approval_request SET payload=jsonb_set(payload,'{reason}','\"changed\"') WHERE approval_id=?", id);
        }
        assertProblem(call(decisionPath(type, destination), mutation.equals("actor") ? otherToken : checkerToken,
                decision, key(), proof), mutation.equals("actor") ? 404 : 409,
                mutation.equals("actor") ? "AUTH_CHALLENGE_NOT_FOUND" : "STEP_UP_CHALLENGE_MISMATCH");
        assertPending(type, id);
        assertUnconsumed(proof);
        assertThat(audits(id, type, "APPROVED")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "limits"})
    void twoCheckersApplyOnlyOnce(String type) throws Exception {
        long id = propose(type);
        String path = decisionPath(type, id);
        String firstProof = proof(path, checkerToken, APPROVE, key());
        String secondProof = proof(path, otherToken, APPROVE, key());
        CyclicBarrier start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(10, TimeUnit.SECONDS); return call(path, checkerToken, APPROVE, key(), firstProof); });
            var second = workers.submit(() -> { start.await(10, TimeUnit.SECONDS); return call(path, otherToken, APPROVE, key(), secondProof); });
            var responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> loser = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertProblem(loser, 409, "APPROVAL_ALREADY_DECIDED");
        }
        assertApplied(type, id);
        assertThat(audits(id, type, "APPROVED")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE challenge_ref IN (?::uuid,?::uuid) AND consumed_at IS NOT NULL",
                firstProof, secondProof)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "limits"})
    void staleVersionAfterAnotherApprovalIsRejected(String type) throws Exception {
        long first = propose(type);
        long stale = propose(type);
        String staleKey = key();
        String staleProof = proof(decisionPath(type, stale), checkerToken, APPROVE, staleKey);
        decide(type, first, APPROVE);
        assertProblem(call(decisionPath(type, stale), checkerToken, APPROVE, staleKey, staleProof), 409, "ROLE_CONFLICT");
        assertThat(status(stale)).isEqualTo("PENDING");
        assertApplied(type, first);
        assertUnconsumed(staleProof);
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key=?", staleKey)).isZero();
        assertThat(audits(stale, type, "APPROVED")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"roles,provider", "limits,provider", "roles,audit", "limits,audit"})
    void failureAfterProviderWriteRollsBackAndSameProofCanRetry(String type, String failure) throws Exception {
        long id = propose(type);
        String key = key();
        String proof = proof(decisionPath(type, id), checkerToken, APPROVE, key);
        String table = failure.equals("audit") ? "audit_log" : type.equals("roles") ? "session" : "limit_config";
        String condition = failure.equals("audit") ? "NEW.action='" + action(type, "APPROVED") + "'"
                : type.equals("roles") ? "NEW.user_id=" + target + " AND NEW.status='LOGGED_OUT'"
                : "NEW.approval_id=" + id + " AND NEW.status='ACTIVE'";
        db.execute("CREATE SEQUENCE banking.int09_fault_reached");
        try (var ignored = trigger(table, failure.equals("audit") ? "BEFORE INSERT" : "AFTER UPDATE", """
                IF %s THEN
                    PERFORM nextval('banking.int09_fault_reached');
                    RAISE EXCEPTION 'Synthetic INT09 failure' USING ERRCODE='58000';
                END IF;
                """.formatted(condition))) {
            db.execute("GRANT USAGE ON SEQUENCE banking.int09_fault_reached TO banking_app");
            assertProblem(call(decisionPath(type, id), checkerToken, APPROVE, key, proof),
                    500, failure.equals("audit") ? "AUDIT_WRITE_FAILED" : "INTERNAL_ERROR");
            assertThat(db.queryForObject("SELECT is_called FROM banking.int09_fault_reached", Boolean.class)).isTrue();
            assertPending(type, id);
            assertUnconsumed(proof);
            assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key=?", key)).isZero();
            assertThat(audits(id, type, "APPROVED")).isZero();
        } finally {
            db.execute("DROP SEQUENCE banking.int09_fault_reached");
        }
        assertStatus(call(decisionPath(type, id), checkerToken, APPROVE, key, proof), 200);
        assertApplied(type, id);
        assertThat(audits(id, type, "APPROVED")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"roles,REQUESTED", "limits,REQUESTED", "roles,REJECTED", "limits,REJECTED"})
    void proposalAndRejectionAuditFailuresAlsoRollBack(String type, String phase) throws Exception {
        boolean proposal = phase.equals("REQUESTED");
        long id = proposal ? 0 : propose(type);
        String path = proposal ? path(type) : decisionPath(type, id);
        String body = proposal ? proposal(type) : "{\"decision\":\"REJECT\"}";
        String token = proposal ? makerToken : checkerToken;
        String key = key();
        String proof = proof(path, token, body, key);
        try (var ignored = trigger("audit_log", "BEFORE INSERT", """
                IF NEW.action='%s' THEN
                    RAISE EXCEPTION 'Synthetic C2 failure' USING ERRCODE='58000';
                END IF;
                """.formatted(action(type, phase)))) {
            assertProblem(call(path, token, body, key, proof), 500, "AUDIT_WRITE_FAILED");
            assertUnconsumed(proof);
            assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key=?", key)).isZero();
            if (proposal) {
                assertThat(count("SELECT count(*) FROM banking.approval_request WHERE target_id=? AND request_type=?",
                        Long.toString(type.equals("roles") ? target : customer),
                        type.equals("roles") ? "ROLE_CHANGE" : "LIMIT_CHANGE")).isZero();
                assertThat(count("SELECT count(*) FROM banking.limit_config WHERE customer_id=?", customer)).isZero();
            } else {
                assertPending(type, id);
                assertThat(audits(id, type, phase)).isZero();
            }
        }
        var retried = call(path, token, body, key, proof);
        assertStatus(retried, proposal ? 201 : 200);
        assertThat(audits(proposal ? id(retried) : id, type, phase)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "limits"})
    void businessWritesFinishBeforeAuditAppend(String type) throws Exception {
        try (var audit = trigger("audit_log", "BEFORE INSERT",
                "PERFORM set_config('int09.audit_phase', txid_current()::text, true);")) {
            var guards = new java.util.ArrayList<AutoCloseable>();
            try {
                for (String table : List.of("approval_request", "admin_idempotency_record", "auth_challenge",
                        "app_user", "user_role", "user_privilege", "session", "limit_config")) {
                    guards.add(trigger(table, "BEFORE INSERT OR UPDATE OR DELETE", """
                            IF current_setting('int09.audit_phase', true)=txid_current()::text THEN
                                RAISE EXCEPTION 'Business write after audit' USING ERRCODE='23514';
                            END IF;
                            """));
                }
                long applied = propose(type);
                decide(type, applied, APPROVE);
                assertApplied(type, applied);
                long rejected = propose(type);
                decide(type, rejected, "{\"decision\":\"REJECT\"}");
                assertThat(status(rejected)).isEqualTo("REJECTED");
                assertThat(audits(rejected, type, "REJECTED")).isEqualTo(1);
            } finally {
                for (var guard : guards.reversed()) guard.close();
            }
        }
    }

    private long propose(String type) throws Exception {
        String key = key();
        String body = proposal(type);
        String proof = proof(path(type), makerToken, body, key);
        var response = call(path(type), makerToken, body, key, proof);
        assertStatus(response, 201);
        return id(response);
    }

    private void decide(String type, long id, String body) throws Exception {
        String key = key();
        String path = decisionPath(type, id);
        String proof = proof(path, checkerToken, body, key);
        assertStatus(call(path, checkerToken, body, key, proof), 200);
    }

    private String proof(String path, String token, String body, String key) throws Exception {
        var required = call(path, token, body, key, null);
        assertProblem(required, 428, "STEP_UP_REQUIRED");
        JsonNode challenge = JSON.readTree(required.body()).path("challenge");
        assertThat(challenge.path("purpose").asText()).isEqualTo("SENSITIVE_ACTION");
        String ref = challenge.path("challengeId").asText();
        String code = CODES.poll(2, TimeUnit.SECONDS);
        assertThat(code).isNotNull();
        assertStatus(call("/api/v1/auth/step-up/verify", token,
                JSON.writeValueAsString(Map.of("challengeId", ref, "method", "OTP", "otp", code)), null, null), 200);
        return ref;
    }

    private HttpResponse<String> call(String path, String token, String body, String key, String proof) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).header("X-Correlation-ID", "int09-" + UUID.randomUUID());
        if (key != null) request.header("Idempotency-Key", key);
        if (proof != null) request.header("X-Step-Up-Challenge", proof);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String proposal(String type) {
        return type.equals("roles") ? "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + target
                + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"synthetic\"}"
                : "{\"customerId\":\"" + customer + "\",\"perTransactionLimit\":\"5000000.00\",\"dailyLimit\":\"20000000.00\",\"reason\":\"synthetic\"}";
    }

    private static String changed(String body) { return body.replace("synthetic", "changed"); }
    private static String path(String type) { return "/api/v1/admin/" + type; }
    private static String decisionPath(String type, long id) { return path(type) + "/" + id + "/approve"; }
    private static String key() { return "int09-" + UUID.randomUUID(); }
    private static long id(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()).path("approvalId").asLong(); }
    private String status(long id) { return db.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id=?", String.class, id); }
    private int count(String sql, Object... args) { return db.queryForObject(sql, Integer.class, args); }
    private static String action(String type, String suffix) { return (type.equals("roles") ? "ROLE" : "LIMIT") + "_CHANGE_" + suffix; }
    private int audits(long id, String type, String suffix) {
        return count("SELECT count(*) FROM banking.audit_log WHERE entity_type='APPROVAL_REQUEST' AND entity_id=? AND action=?",
                Long.toString(id), action(type, suffix));
    }

    private void assertPending(String type, long id) {
        assertThat(status(id)).isEqualTo("PENDING");
        if (type.equals("roles")) {
            assertThat(count("SELECT count(*) FROM banking.user_role WHERE user_id=?", target)).isZero();
            assertThat(count("SELECT role_assignment_version FROM banking.app_user WHERE user_id=?", target)).isZero();
            assertThat(db.queryForObject("SELECT status FROM banking.session WHERE session_token_hash=?", String.class,
                    HmacService.sha256Hex(targetToken))).isEqualTo("ACTIVE");
        } else {
            assertThat(db.queryForObject("SELECT status FROM banking.limit_config WHERE approval_id=?", String.class, id)).isEqualTo("PENDING_APPROVAL");
            assertThat(count("SELECT count(*) FROM banking.limit_config WHERE customer_id=? AND status='ACTIVE'", customer)).isZero();
        }
    }

    private void assertApplied(String type, long id) {
        assertThat(status(id)).isEqualTo("APPLIED");
        if (type.equals("roles")) {
            assertThat(count("SELECT count(*) FROM banking.user_role WHERE user_id=?", target)).isEqualTo(1);
            assertThat(count("SELECT role_assignment_version FROM banking.app_user WHERE user_id=?", target)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT status FROM banking.session WHERE session_token_hash=?", String.class,
                    HmacService.sha256Hex(targetToken))).isEqualTo("LOGGED_OUT");
        } else {
            assertThat(count("SELECT count(*) FROM banking.limit_config WHERE customer_id=? AND status='ACTIVE' AND version=1", customer)).isEqualTo(1);
        }
    }

    private void assertUnconsumed(String proof) {
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE challenge_ref=?::uuid AND status='VERIFIED' AND consumed_at IS NULL", proof)).isEqualTo(1);
    }

    private static void assertStatus(HttpResponse<String> response, int status) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); }
    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertStatus(response, status);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private String session(long user) {
        String token = key();
        db.update("INSERT INTO banking.session(session_token_hash,csrf_token_hash,user_id,idle_expires_at,absolute_expires_at) "
                + "VALUES (?, ?, ?, now()+interval '20 minutes', now()+interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), user);
        return token;
    }

    private AutoCloseable trigger(String table, String timing, String body) {
        String name = "int09_" + table;
        db.execute("CREATE FUNCTION banking." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + body + " RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER " + name + " " + timing + " ON banking." + table
                + " FOR EACH ROW EXECUTE FUNCTION banking." + name + "()");
        return () -> {
            db.execute("DROP TRIGGER " + name + " ON banking." + table);
            db.execute("DROP FUNCTION banking." + name + "()");
        };
    }

    private static HttpServer smsBoundary() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sms", exchange -> {
                try (exchange) {
                    CODES.add(JSON.readTree(exchange.getRequestBody()).path("otp").asText());
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    @AfterAll
    static void stopSms() {
        POSTGRES.getLogs().lines().filter(line -> line.contains("ERROR:")).distinct().forEach(System.out::println);
        SMS.stop(0);
    }
}
