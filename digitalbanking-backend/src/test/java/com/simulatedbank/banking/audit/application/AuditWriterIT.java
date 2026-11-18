package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.audit.domain.AuditHashing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AuditWriterIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("audit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired AuditRecorder recorder;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void writesHashChainedRecordsInsideCallerTransaction() {
        String reference = "TXN20261103A1B2C3D4";
        transactions.executeWithoutResult(status -> {
            recorder.recordInTransaction(event(reference), Instant.parse("2026-11-03T10:15:31.123456Z"));
            recorder.recordInTransaction(event(reference), Instant.parse("2026-11-03T10:15:32.123456Z"));
        });
        var rows = jdbc.queryForList("SELECT prev_hash, record_hash FROM banking.audit_log WHERE entity_id = ? ORDER BY audit_id", reference);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("prev_hash")).isEqualTo(rows.get(0).get("record_hash"));
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("banking_app");
    }

    @Test
    void storesTimestampAtSameMicrosecondAsHashedPayload() {
        String reference = "TXN20261103TIMEPRECISION";
        transactions.executeWithoutResult(status -> recorder.recordInTransaction(event(reference),
                Instant.parse("2026-11-03T10:15:31.123456789Z")));
        var row = jdbc.queryForMap("SELECT audit_id, occurred_at, canonical_payload::text AS canonical_payload, "
                + "canonical_payload->>'occurred_at' AS hashed_time, prev_hash, record_hash "
                + "FROM banking.audit_log WHERE entity_id = ?", reference);
        assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant())
                .isEqualTo(Instant.parse((String) row.get("hashed_time")));
    }

    @Test
    void rejectsCallsWithoutAnExistingTransaction() {
        assertThatThrownBy(() -> recorder.recordInTransaction(event("TXN20261103NOACTIVE"), Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void callerRollbackRemovesAuditAndBusinessMutation() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (701)");
            recorder.recordInTransaction(event("TXN20261103ROLLBACK"), Instant.now());
            throw new IllegalStateException("injected caller failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=701", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE entity_id='TXN20261103ROLLBACK'", Long.class)).isZero();
    }

    @Test
    void auditValidationFailureRollsBackCallerEvenWhenCallerCatchesIt() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (702)");
            try {
                recorder.recordInTransaction(event("TXN20261103BADTIME"), null);
            } catch (IllegalArgumentException expected) {
                // A rejected audit must still prevent committing the caller's mutation.
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=702", Long.class)).isZero();
    }

    @Test
    void missingOptionalObjectsAreStoredAsSqlNull() {
        String reference = "TXN20261103NULLSTATE";
        var created = new AuditEvent("TRANSFER_CREATED", "USER", 7L, "CUSTOMER", "TRANSFER", reference,
                null, Map.of("status", "PENDING"), new BigDecimal("1.00"), "VND",
                "corr-1", "req-1", "SUCCESS", null);
        transactions.executeWithoutResult(status -> recorder.recordInTransaction(created, Instant.now()));
        var row = jdbc.queryForMap("SELECT before_state IS NULL AS before_is_null, "
                + "client_info_masked IS NULL AS client_is_null, "
                + "canonical_payload->'before_state' = 'null'::jsonb AS payload_null "
                + "FROM banking.audit_log WHERE entity_id = ?", reference);
        assertThat(row).containsEntry("before_is_null", true).containsEntry("client_is_null", true)
                .containsEntry("payload_null", true);
    }

    @Test
    void rejectsSecurityBestEffortActionInAtomicWriter() {
        var security = new AuditEvent("AUTH_LOGIN_FAILED", "SYSTEM", null, "ANONYMOUS", "APP_USER", "anon:test",
                null, Map.of("reasonCode", "INVALID_CREDENTIALS"), null, null,
                "corr-1", "req-1", "FAILURE", Map.of("ipMasked", "203.0.113.***"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (703)");
            recorder.recordInTransaction(security, Instant.now());
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=703", Long.class)).isZero();
    }

    @Test
    void caughtDatabaseInsertFailureRollsBackBusinessAndKeepsChainUsable() throws Exception {
        String reference = "TXN20261103DBFAIL";
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION banking.fail_audit_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.entity_id = 'TXN20261103DBFAIL' THEN
                            RAISE EXCEPTION 'Injected audit insert failure' USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END;
                    $$;
                    CREATE TRIGGER audit_test_insert_failure BEFORE INSERT ON banking.audit_log
                    FOR EACH ROW EXECUTE FUNCTION banking.fail_audit_insert();
                    """);
        }
        String previous = jdbc.queryForObject("SELECT COALESCE((SELECT record_hash FROM banking.audit_log ORDER BY audit_id DESC LIMIT 1), ?)",
                String.class, AuditHashing.GENESIS_HASH);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (704)");
            try {
                recorder.recordInTransaction(event(reference), Instant.now());
            } catch (org.springframework.dao.DataAccessException expected) {
                // Catching a failed audit insert must not allow the caller to commit.
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=704", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE entity_id = ?", Long.class, reference)).isZero();
        transactions.executeWithoutResult(status -> recorder.recordInTransaction(event("TXN20261103AFTERDBFAIL"), Instant.now()));
        assertThat(jdbc.queryForObject("SELECT prev_hash FROM banking.audit_log WHERE entity_id='TXN20261103AFTERDBFAIL'", String.class))
                .isEqualTo(previous);
    }

    @Test
    void rejectsRepeatableReadSnapshotThatCouldForkTheChain() {
        var repeatable = new TransactionTemplate(transactions.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (705)");
            recorder.recordInTransaction(event("TXN20261103ISOLATION"), Instant.now());
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=705", Long.class)).isZero();
    }

    @Test
    void chainLockTimeoutRollsBackCallerAndReleasesResources() throws Exception {
        try (var blocker = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator")) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0))");
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                    jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (706)");
                    try {
                        recorder.recordInTransaction(event("TXN20261103LOCKTIMEOUT"), Instant.now());
                    } catch (org.springframework.dao.DataAccessException expected) {
                        // Even a caught lock timeout must prevent the business transaction from committing.
                    }
                })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            } finally {
                blocker.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id=706", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE entity_id='TXN20261103LOCKTIMEOUT'", Long.class)).isZero();
        transactions.executeWithoutResult(status -> recorder.recordInTransaction(event("TXN20261103AFTERTIMEOUT"), Instant.now()));
    }

    @Test
    void appendOnlyTriggersProtectRowsEvenForTableOwner() throws Exception {
        var ref = transactions.execute(status -> recorder.recordInTransaction(event("TXN20261103TRIGGER"), Instant.now()));
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            for (String sql : java.util.List.of("UPDATE banking.audit_log SET actor_role='FORGED' WHERE audit_id=" + ref.auditId(),
                    "DELETE FROM banking.audit_log WHERE audit_id=" + ref.auditId(), "TRUNCATE banking.audit_log")) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(java.sql.SQLException.class)
                        .satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(jdbc.queryForObject("SELECT record_hash FROM banking.audit_log WHERE audit_id=?", String.class, ref.auditId()))
                .isEqualTo(ref.recordHash());
        var grants = jdbc.queryForMap("""
                SELECT has_table_privilege(current_user, 'banking.audit_log', 'INSERT') AS can_insert,
                       has_table_privilege(current_user, 'banking.audit_log', 'SELECT') AS can_select,
                       has_table_privilege(current_user, 'banking.audit_log', 'UPDATE') AS can_update,
                       has_table_privilege(current_user, 'banking.audit_log', 'DELETE') AS can_delete,
                       has_table_privilege(current_user, 'banking.audit_log', 'TRUNCATE') AS can_truncate,
                       has_sequence_privilege(current_user, 'banking.audit_log_audit_id_seq', 'USAGE') AS can_use_sequence
                """);
        assertThat(grants).containsEntry("can_insert", true).containsEntry("can_select", true)
                .containsEntry("can_update", false).containsEntry("can_delete", false)
                .containsEntry("can_truncate", false).containsEntry("can_use_sequence", true);
    }

    @Test
    void normalizedSnapshotsMatchCanonicalPayloadAndRowColumns() {
        String reference = "TXN20261103CONSISTENCY";
        var value = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", reference,
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED", "sourceAccountId", Long.MAX_VALUE,
                        "amount", new BigDecimal("150000.5"), "currency", "VND"),
                new BigDecimal("150000.50"), "VND", "corr-1", "req-1", "SUCCESS", null);
        transactions.executeWithoutResult(status -> recorder.recordInTransaction(value, Instant.parse("2026-11-03T10:15:31.123456Z")));
        Boolean matches = jdbc.queryForObject("""
                SELECT canonical_payload->'after_state' = after_state
                   AND canonical_payload->'before_state' = before_state
                   AND canonical_payload->>'actor_type' = actor_type
                   AND canonical_payload->>'actor_user_id' = actor_user_id::text
                   AND canonical_payload->>'actor_role' = actor_role
                   AND canonical_payload->>'action' = action
                   AND canonical_payload->>'entity_type' = entity_type
                   AND canonical_payload->>'entity_id' = entity_id
                   AND canonical_payload->>'amount' = amount::text
                   AND canonical_payload->>'currency' = currency::text
                   AND canonical_payload->>'correlation_id' = correlation_id
                   AND canonical_payload->>'request_id' = request_id
                   AND canonical_payload->>'outcome' = outcome
                   AND canonical_payload->>'hash_version' = hash_version::text
                   AND jsonb_typeof(after_state->'sourceAccountId') = 'string'
                   AND after_state->>'sourceAccountId' = '9223372036854775807'
                   AND after_state->>'amount' = '150000.50'
                FROM banking.audit_log WHERE entity_id = ?
                """, Boolean.class, reference);
        assertThat(matches).isTrue();
    }

    @Test
    void runtimeRoleCannotUpdateDeleteOrTruncateAudit() {
        assertThatThrownBy(() -> jdbc.update("UPDATE banking.audit_log SET actor_role='FORGED'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM banking.audit_log"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE banking.audit_log"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void concurrentTransactionsProduceOneChainAndIndependentHashes() throws Exception {
        int writers = 8;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < writers; index++) {
                int writer = index;
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                    transactions.executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (?)", 800L + writer);
                        recorder.recordInTransaction(event("TXN20261103CONCURRENT" + writer),
                                Instant.parse("2026-11-03T12:00:00Z"));
                    });
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id BETWEEN 800 AND 807", Long.class))
                .isEqualTo((long) writers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE entity_id LIKE 'TXN20261103CONCURRENT%'", Long.class))
                .isEqualTo((long) writers);
        var rows = jdbc.queryForList("SELECT canonical_payload::text AS canonical_payload, prev_hash, record_hash FROM banking.audit_log ORDER BY audit_id");
        String previous = AuditHashing.GENESIS_HASH;
        for (var row : rows) {
            assertThat(row.get("prev_hash")).isEqualTo(previous);
            String canonical = new org.erdtman.jcs.JsonCanonicalizer(
                    (String) row.get("canonical_payload")).getEncodedString();
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((canonical + "|" + previous).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(row.get("record_hash")).isEqualTo(java.util.HexFormat.of().formatHex(bytes));
            previous = (String) row.get("record_hash");
        }
    }

    private static AuditEvent event(String reference) {
        return new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", reference,
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"),
                "VND", "corr-1", "req-1", "SUCCESS", null);
    }
}
