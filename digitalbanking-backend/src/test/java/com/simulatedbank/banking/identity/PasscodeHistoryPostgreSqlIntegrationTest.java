package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.in.PasscodeLoginRequest;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.application.PasswordHistoryService;
import com.simulatedbank.banking.identity.adapter.out.UserCredentialEntity;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real V1/V2, HTTP/security and database transactions. No production device-proof mechanism is invented. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PasscodeHistoryPostgreSqlIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Argon2PasswordHasher HASHER = new Argon2PasswordHasher();
    private static final String ORIGINAL = "Original#123";
    private static final String NEXT = "NextPassword#456";
    private static final String ORIGINAL_HASH = HASHER.hash(ORIGINAL.toCharArray());
    private static final List<String> PRIOR = List.of("PriorAlpha#1", "PriorBravo#2", "PriorCharlie#3", "PriorDelta#4");
    private static final List<String> PRIOR_HASHES = PRIOR.stream().map(value -> HASHER.hash(value.toCharArray())).toList();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("passcode_history").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordHistoryService history;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @LocalServerPort private int port;
    private TransactionTemplate tx;
    private long userId;
    private String username;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        username = "id09-" + UUID.randomUUID();
        userId = seedUser(username);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void freshPasswordArchivesOnlyOldHashAndUpdatesCredentialAndTimestamp() {
        Map<String, Object> before = credential();
        char[] candidate = NEXT.toCharArray();
        tx.executeWithoutResult(status -> history.replacePassword(userId, candidate));
        assertThat(candidate).containsOnly('\0');
        Map<String, Object> after = credential();
        assertThat(HASHER.matches(after.get("password_hash").toString(), NEXT.toCharArray())).isTrue();
        assertThat(HASHER.matches(after.get("password_hash").toString(), ORIGINAL.toCharArray())).isFalse();
        assertThat(after.get("password_changed_at")).isNotEqualTo(before.get("password_changed_at"));
        assertThat(hashes()).containsExactly(ORIGINAL_HASH);
        assertThat(after.get("password_hash").toString()).startsWith("$argon2id$")
                .doesNotContain(ORIGINAL, NEXT);
    }

    @Test
    void rejectsCurrentPasswordEvenWithoutHistory() {
        assertRejectedWithoutChanges(ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsEachOfThreePreviousPasswordsWithoutDisclosingMatch(int recency) {
        seedHistory();
        assertRejectedWithoutChanges(PRIOR.get(4 - recency));
    }

    @Test
    void fourthPreviousPasswordIsOutsideWindowAndHistoryIsNotPurged() {
        seedHistory();
        replace(PRIOR.getFirst());
        assertThat(HASHER.matches(credential().get("password_hash").toString(), PRIOR.getFirst().toCharArray())).isTrue();
        assertThat(hashes()).containsExactly(ORIGINAL_HASH, PRIOR_HASHES.get(3), PRIOR_HASHES.get(2),
                PRIOR_HASHES.get(1), PRIOR_HASHES.getFirst());
    }

    @Test
    void historyAndCredentialAreScopedToServerSuppliedUser() {
        long other = seedUser("other-" + UUID.randomUUID());
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.password_history (user_id, password_hash) VALUES (?, ?)
                """, other, PRIOR_HASHES.getLast()));
        replace(PRIOR.getLast());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM banking.app_user WHERE user_id = ?",
                String.class, other)).isEqualTo(ORIGINAL_HASH);
        assertThat(jdbc.queryForList("SELECT password_hash FROM banking.password_history WHERE user_id = ?",
                String.class, other)).containsExactly(PRIOR_HASHES.getLast());
        assertThat(hashes()).containsExactly(ORIGINAL_HASH);
    }

    @Test
    void callerRollbackRestoresCredentialAndHistoryTogether() {
        Map<String, Object> before = credential();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            history.replacePassword(userId, NEXT.toCharArray());
            throw new IllegalStateException("caller failed before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(credential()).isEqualTo(before);
        assertThat(hashes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password_history", "app_user"})
    void databaseWriteFailureRollsBackBothHistoryAndCredential(String table) {
        String constraint = "ck_id09_failure_" + userId;
        Map<String, Object> before = credential();
        tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking." + table + " ADD CONSTRAINT "
                + constraint + " CHECK (user_id <> " + userId
                + (table.equals("app_user") ? " OR password_hash = '" + ORIGINAL_HASH + "'" : "") + ")"));
        try {
            char[] candidate = NEXT.toCharArray();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> history.replacePassword(userId, candidate)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(candidate).containsOnly('\0');
            assertThat(credential()).isEqualTo(before);
            assertThat(hashes()).isEmpty();
        } finally {
            tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking." + table
                    + " DROP CONSTRAINT " + constraint));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveAccountCannotChangeCredentials(String state) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.app_user SET status = ? WHERE user_id = ?",
                state, userId));
        Map<String, Object> before = credential();
        assertThatThrownBy(() -> replace(NEXT)).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(state.equals("LOCKED")
                        ? ErrorCode.AUTH_ACCOUNT_LOCKED : ErrorCode.AUTH_FORBIDDEN));
        assertThat(credential()).isEqualTo(before);
        assertThat(hashes()).isEmpty();
    }

    @Test
    void nonexistentUserAndMissingCallerTransactionCannotWriteHistory() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> history.replacePassword(Long.MAX_VALUE,
                NEXT.toCharArray()))).isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        char[] candidate = NEXT.toCharArray();
        try {
            assertThatThrownBy(() -> history.replacePassword(userId, candidate))
                    .isInstanceOf(IllegalTransactionStateException.class);
        } finally {
            // A rejected MANDATORY proxy invocation never enters the component; caller owns its buffer.
            Arrays.fill(candidate, '\0');
        }
        assertThat(hashes()).isEmpty();
    }

    @Test
    void malformedHistoricalHashFailsClosedWithoutChangingAnything() {
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.password_history (user_id, password_hash) VALUES (?, 'invalid-hash')
                """, userId));
        Map<String, Object> before = credential();
        assertThatThrownBy(() -> replace(NEXT))
                .isInstanceOf(com.simulatedbank.banking.identity.application.PasswordHashFormatException.class);
        assertThat(credential()).isEqualTo(before);
        assertThat(hashes()).containsExactly("invalid-hash");
    }

    @Test
    void concurrentSamePasswordWaitsForUserLockThenRejectsReuse() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var firstChanged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String application = "id09-waiter-" + userId;
        try {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                history.replacePassword(userId, NEXT.toCharArray());
                firstChanged.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("lock holder timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(firstChanged.await(15, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, application);
                        history.replacePassword(userId, NEXT.toCharArray());
                    });
                    return "ACCEPTED";
                } catch (BankingBusinessException rejected) {
                    return rejected.code().name();
                }
            });
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                    tx.execute(status -> {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()");
                        return Boolean.TRUE.equals(jdbc.queryForObject("""
                                SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                 WHERE application_name = ? AND cardinality(pg_blocking_pids(pid)) > 0)
                                """, Boolean.class, application));
                    }));
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("VALIDATION_ERROR");
            assertThat(hashes()).containsExactly(ORIGINAL_HASH);
            assertThat(HASHER.matches(credential().get("password_hash").toString(), NEXT.toCharArray())).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staleCallerPersistenceContextCannotAuthorizePasswordReuse() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                // Preload without locking; a different transaction can change the credential afterwards.
                entityManager.find(UserCredentialEntity.class, userId);
                var changed = pool.submit(() -> replace(NEXT));
                try {
                    changed.get(15, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                history.replacePassword(userId, NEXT.toCharArray());
            })).isInstanceOfSatisfying(BankingBusinessException.class,
                    failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            assertThat(hashes()).containsExactly(ORIGINAL_HASH);
            assertThat(HASHER.matches(credential().get("password_hash").toString(), NEXT.toCharArray())).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void multipleChangesInOneCallerTransactionArchiveEachManagedCredential() {
        tx.executeWithoutResult(status -> {
            history.replacePassword(userId, NEXT.toCharArray());
            history.replacePassword(userId, "ThirdPassword#789".toCharArray());
        });
        var previous = hashes();
        assertThat(previous).hasSize(2);
        assertThat(HASHER.matches(previous.getFirst(), NEXT.toCharArray())).isTrue();
        assertThat(previous.getLast()).isEqualTo(ORIGINAL_HASH);
        assertThat(HASHER.matches(credential().get("password_hash").toString(), "ThirdPassword#789".toCharArray()))
                .isTrue();
    }

    @Test
    void successiveChangesRecordEveryPreviousCredentialAndRejectEarlierReuse() {
        replace(NEXT);
        String intermediate = credential().get("password_hash").toString();
        replace("ThirdPassword#789");
        assertThat(hashes()).containsExactly(intermediate, ORIGINAL_HASH);
        assertRejectedWithoutChanges(NEXT);
        assertRejectedWithoutChanges(ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "untrusted", "revoked", "trusted", "foreign", "unregistered"})
    void noMetadataOrTrustedDatabaseFlagCanAuthorizePasscode(String scenario) throws Exception {
        UUID ref = UUID.randomUUID();
        if (!scenario.equals("unknown") && !scenario.equals("unregistered")) {
            long owner = scenario.equals("foreign") ? seedUser("foreign-" + UUID.randomUUID()) : userId;
            tx.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO banking.device
                        (device_ref, user_id, device_name, device_fingerprint_hash, trusted, status, passcode_hash)
                    VALUES (?, ?, 'Test device', ?, ?, ?, ?)
                    """, ref, owner, HmacService.sha256Hex("copied-fingerprint"),
                    scenario.equals("trusted") || scenario.equals("revoked"),
                    scenario.equals("revoked") ? "REVOKED" : "ACTIVE", ORIGINAL_HASH));
        }
        Map<String, Object> before = credential();
        var response = passcode(Map.of("username", username, "passcode", "123456", "deviceRef", ref.toString(),
                "deviceFingerprint", "copied-fingerprint"), Map.of("X-Device-Trusted", "true", "X-User-Id", "1"));
        assertProblem(response, 403, "AUTH_DEVICE_NOT_TRUSTED");
        assertThat(credential()).isEqualTo(before);
        assertThat(count("session")).isZero();
        assertThat(count("password_history")).isZero();
        assertThat(count("auth_challenge")).isZero();
    }

    @Test
    void unknownUsernameAndWrongPasscodeReceiveSameUnprovenDeviceDenial() throws Exception {
        for (String name : List.of(username, "unknown-" + UUID.randomUUID())) {
            for (String code : List.of("000000", "87654321")) {
                assertProblem(passcode(Map.of("username", name, "passcode", code,
                        "deviceRef", UUID.randomUUID().toString(), "deviceFingerprint", "manipulated"), Map.of()),
                        403, "AUTH_DEVICE_NOT_TRUSTED");
            }
        }
        assertThat(count("session")).isZero();
    }

    @Test
    void manipulatedFieldsAndNonStringPasscodesAreRejectedByHttpContract() throws Exception {
        Map<String, Object> valid = Map.of("username", username, "passcode", "123456",
                "deviceRef", UUID.randomUUID().toString(), "deviceFingerprint", "fingerprint");
        List<Map<String, Object>> invalid = new ArrayList<>();
        List<Map<String, Object>> malformed = new ArrayList<>();
        for (Object value : List.of(123456, List.of("1", "2", "3", "4", "5", "6"))) {
            var body = new HashMap<>(valid);
            body.put("passcode", value);
            malformed.add(body);
        }
        for (String value : List.of("12345", "123456789", "12a456")) {
            var body = new HashMap<>(valid);
            body.put("passcode", value);
            invalid.add(body);
        }
        for (String field : valid.keySet()) {
            var body = new HashMap<>(valid);
            body.remove(field);
            invalid.add(body);
        }
        for (String ref : List.of("1-1-1-1-1", "not-a-uuid")) {
            var body = new HashMap<>(valid);
            body.put("deviceRef", ref);
            invalid.add(body);
        }
        for (String field : List.of("trusted", "userId", "deviceId", "method")) {
            var body = new HashMap<>(valid);
            body.put(field, "true");
            malformed.add(body);
        }
        for (var body : invalid) {
            assertProblem(passcode(body, Map.of()), 400, "VALIDATION_ERROR");
        }
        for (var body : malformed) {
            assertProblem(passcode(body, Map.of()), 400, "MALFORMED_REQUEST");
        }
        assertThat(count("session")).isZero();
    }

    @Test
    void revokedOrExpiredSessionCannotSupplyDeviceProof() throws Exception {
        String token = "session-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.session
                    (session_token_hash, csrf_token_hash, user_id, status, idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, 'LOGGED_OUT', now() + interval '1 minute', now() + interval '1 hour')
                """, HmacService.sha256Hex(token), HmacService.sha256Hex("csrf"), userId));
        assertProblem(passcode(Map.of("username", username, "passcode", "123456",
                "deviceRef", UUID.randomUUID().toString(), "deviceFingerprint", "fingerprint"),
                Map.of("Authorization", "Bearer " + token)), 401, "AUTH_SESSION_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.session WHERE session_token_hash = ?",
                String.class, HmacService.sha256Hex(token))).isEqualTo("LOGGED_OUT");
    }

    @Test
    void passcodeRequestNeverSerializesOrPrintsTheCredential() throws Exception {
        var request = new PasscodeLoginRequest(username, "123456".toCharArray(),
                UUID.randomUUID().toString(), "fingerprint");
        assertThat(JSON.writeValueAsString(request)).doesNotContain("123456", "passcode");
        assertThat(request.toString()).doesNotContain("123456", username, "fingerprint");
    }

    private void replace(String password) {
        tx.executeWithoutResult(status -> history.replacePassword(userId, password.toCharArray()));
    }

    private void assertRejectedWithoutChanges(String candidate) {
        Map<String, Object> before = credential();
        List<String> oldHistory = hashes();
        char[] buffer = candidate.toCharArray();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> history.replacePassword(userId, buffer)))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(failure.getMessage()).doesNotContain(candidate, "history", "hash");
                    assertThat(failure.extensions()).isEmpty();
                });
        assertThat(buffer).containsOnly('\0');
        assertThat(credential()).isEqualTo(before);
        assertThat(hashes()).isEqualTo(oldHistory);
    }

    private long seedUser(String name) {
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, ?, 'CUSTOMER') RETURNING user_id
                """, Long.class, name, ORIGINAL_HASH));
    }

    private void seedHistory() {
        tx.executeWithoutResult(status -> {
            // Deliberately equal timestamps: history_id must break ties deterministically.
            for (String hash : PRIOR_HASHES) {
                jdbc.update("""
                        INSERT INTO banking.password_history (user_id, password_hash, created_at)
                        VALUES (?, ?, '2020-01-01T00:00:00Z')
                        """, userId, hash);
            }
        });
    }

    private Map<String, Object> credential() {
        return jdbc.queryForMap("SELECT * FROM banking.app_user WHERE user_id = ?", userId);
    }

    private List<String> hashes() {
        return jdbc.queryForList("""
                SELECT password_hash FROM banking.password_history WHERE user_id = ?
                 ORDER BY created_at DESC, history_id DESC
                """, String.class, userId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM banking." + table + " WHERE user_id = ?", Long.class, userId);
    }

    private HttpResponse<String> passcode(Map<String, Object> body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/passcode"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        JsonNode problem = JSON.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).doesNotContain(ORIGINAL, NEXT, "123456", "password_hash", "passcode_hash");
    }
}
