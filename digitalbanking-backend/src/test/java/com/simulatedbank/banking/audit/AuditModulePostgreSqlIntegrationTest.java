package com.simulatedbank.banking.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.audit.adapter.out.JdbcAuditWriter;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.audit.application.AuditChainWriter;
import com.simulatedbank.banking.audit.application.AuditEventValidator;
import com.simulatedbank.banking.audit.application.AuditModuleService;
import com.simulatedbank.banking.audit.domain.AuditChain;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 16 evidence for the durable audit provider: caller-transaction C2, best-effort C3, append-only
 * triggers, the hash chain under concurrent writers and the absence of writer-side deduplication.
 *
 * <p>Only the audit runtime is wired (no full application context) so the test proves the production beans and
 * their real {@code MANDATORY}/{@code REQUIRES_NEW} proxies against Flyway V1–V11, without depending on other
 * modules' in-progress wiring.</p>
 */
@SpringJUnitConfig(AuditModulePostgreSqlIntegrationTest.Config.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditModulePostgreSqlIntegrationTest {

    private static final String EXPECTED_CANONICAL_JSON = "{\"action\":\"TRANSFER_COMPLETED\","
            + "\"actor_role\":\"SYSTEM\",\"actor_type\":\"SYSTEM\",\"actor_user_id\":null,"
            + "\"after_state\":{\"status\":\"COMPLETED\"},\"amount\":\"150000.00\","
            + "\"before_state\":{\"status\":\"PENDING\"},"
            + "\"client_info_masked\":{\"ipMasked\":\"203.0.113.***\"},"
            + "\"correlation_id\":\"corr-audit-1\",\"currency\":\"VND\","
            + "\"entity_id\":\"TXN20261103A1B2C3D4\",\"entity_type\":\"TRANSFER\",\"hash_version\":1,"
            + "\"occurred_at\":\"2026-11-03T10:15:31.123456Z\",\"outcome\":\"SUCCESS\","
            + "\"request_id\":\"req-audit-1\"}";

    private static final String EXPECTED_FIRST_RECORD_HASH =
            "16ed00a3030b394413a3aa1956e65307d813b48414ed31eece6f4a23d5efc28b";

    private static final Instant KNOWN_OCCURRED_AT = Instant.parse("2026-11-03T10:15:31.123456Z");

    @Container
    static final PostgreSQLContainer<?> postgres = startContainer();

    private static PostgreSQLContainer<?> startContainer() {
        PostgreSQLContainer<?> container = PostgreSqlIntegrationTestSupport.newContainer("audit_chain");
        container.start();
        return container;
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
        }

        @Bean(initMethod = "migrate")
        Flyway flyway() {
            return PostgreSqlIntegrationTestSupport.flyway(postgres);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JcsCanonicalizer jcsCanonicalizer() {
            return new JcsCanonicalizer();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AuditEventValidator auditEventValidator(ObjectMapper json, JcsCanonicalizer canonicalizer) {
            return new AuditEventValidator(json, canonicalizer);
        }

        @Bean
        AuditChainWriter auditChainWriter(JdbcTemplate jdbc, ObjectMapper json) {
            return new JdbcAuditWriter(jdbc, json);
        }

        @Bean
        AuditModuleApi auditModuleApi(AuditEventValidator validator, AuditChainWriter writer,
                MeterRegistry meters) {
            return new AuditModuleService(validator, writer, meters);
        }
    }

    @Autowired
    private AuditModuleApi audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    @Order(1)
    @DisplayName("C2 record is inserted in the caller transaction with the fixed genesis hash vector")
    void c2RecordCommitsWithCallerTransaction() {
        assertThat(auditCount()).isZero();
        assertThat(AuditChain.recordHash(EXPECTED_CANONICAL_JSON, AuditChain.GENESIS_PREV_HASH))
                .isEqualTo(EXPECTED_FIRST_RECORD_HASH);

        AuditRecordRef reference = transaction.execute(status -> audit.recordInTransaction(knownEvent()));

        assertThat(reference).isNotNull();
        assertThat(reference.auditId()).isPositive();
        assertThat(reference.recordHash()).isEqualTo(EXPECTED_FIRST_RECORD_HASH);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prev_hash, record_hash, hash_version, actor_type, entity_id, amount::text AS amount_text"
                        + " FROM banking.audit_log WHERE audit_id = ?", reference.auditId());
        assertThat(((String) row.get("prev_hash")).trim()).isEqualTo(AuditChain.GENESIS_PREV_HASH);
        assertThat(((String) row.get("record_hash")).trim()).isEqualTo(EXPECTED_FIRST_RECORD_HASH);
        assertThat(((Number) row.get("hash_version")).intValue()).isEqualTo(1);
        assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(row.get("entity_id")).isEqualTo("TXN20261103A1B2C3D4");
        assertThat(row.get("amount_text")).isEqualTo("150000.00");

        OffsetDateTime persisted = jdbc.queryForObject(
                "SELECT occurred_at FROM banking.audit_log WHERE audit_id = ?", OffsetDateTime.class,
                reference.auditId());
        assertThat(persisted.toInstant()).isEqualTo(KNOWN_OCCURRED_AT);

        assertThat(auditCount()).isOne();
        assertChainValid();
    }

    @Test
    @Order(2)
    @DisplayName("C2 insert rolls back together with the caller transaction")
    void c2RollsBackWithCaller() {
        long before = auditCount();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            audit.recordInTransaction(systemEvent(AuditAction.TRANSFER_COMPLETED, "corr-rollback"));
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(auditCount()).isEqualTo(before);
        assertThat(countByCorrelation("corr-rollback")).isZero();
        assertChainValid();
    }

    @Test
    @Order(3)
    @DisplayName("C3 record commits in its own transaction even after the caller transaction fails")
    void c3CommitsAfterCallerFailure() {
        long before = auditCount();
        AuditEvent c3 = new AuditEvent(AuditAction.AUTH_LOGIN_FAILED, AuditActorType.SYSTEM, null, "SYSTEM",
                "APP_USER", "anon:0123456789abcdef", null,
                Map.<String, Object>of("reasonCode", "BAD_CREDENTIALS"), null, null,
                AuditOutcome.FAILURE, "corr-standalone", null, null, Instant.now());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            audit.recordStandalone(c3);
            audit.recordInTransaction(systemEvent(AuditAction.TRANSFER_COMPLETED, "corr-caller-c2"));
            throw new IllegalStateException("caller failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(auditCount()).isEqualTo(before + 1);
        assertThat(countByCorrelation("corr-standalone")).isOne();
        assertThat(countByCorrelation("corr-caller-c2")).isZero();
        assertChainValid();
    }

    @Test
    @Order(4)
    @DisplayName("recordInTransaction without a caller transaction fails before any insert")
    void c2RequiresCallerTransaction() {
        long before = auditCount();

        assertThatThrownBy(() -> audit.recordInTransaction(
                systemEvent(AuditAction.TRANSFER_COMPLETED, "corr-no-tx")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(auditCount()).isEqualTo(before);
        assertThat(countByCorrelation("corr-no-tx")).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("append-only triggers reject UPDATE, DELETE and TRUNCATE")
    void appendOnlyTriggersRejectMutations() {
        Long auditId = jdbc.queryForObject("SELECT min(audit_id) FROM banking.audit_log", Long.class);
        assertThat(auditId).isNotNull();
        long before = auditCount();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE banking.audit_log SET actor_role = 'HACKED' WHERE audit_id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM banking.audit_log WHERE audit_id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE banking.audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(auditCount()).isEqualTo(before);
        assertChainValid();
    }

    @Test
    @Order(6)
    @DisplayName("the writer does not deduplicate identical events")
    void duplicateEventsAreAppendedTwice() {
        long before = auditCount();
        AuditEvent duplicate = systemEvent(AuditAction.TRANSFER_COMPLETED, "corr-duplicate");

        AuditRecordRef first = transaction.execute(status -> audit.recordInTransaction(duplicate));
        AuditRecordRef second = transaction.execute(status -> audit.recordInTransaction(duplicate));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.auditId()).isNotEqualTo(first.auditId());
        assertThat(second.recordHash()).isNotEqualTo(first.recordHash());
        assertThat(auditCount()).isEqualTo(before + 2);
        assertThat(countByCorrelation("corr-duplicate")).isEqualTo(2);
        assertChainValid();
    }

    @Test
    @Order(7)
    @DisplayName("concurrent writers produce one unbroken chain ordered by audit_id")
    void concurrentWritersKeepChainValid() throws Exception {
        long before = auditCount();
        int writers = 2;
        int perWriter = 12;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int writerIndex = 0; writerIndex < writers; writerIndex++) {
                int index = writerIndex;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int eventIndex = 0; eventIndex < perWriter; eventIndex++) {
                        AuditEvent event = systemEvent(AuditAction.TRANSFER_COMPLETED,
                                "corr-concurrent-" + index + "-" + eventIndex);
                        transaction.executeWithoutResult(status -> audit.recordInTransaction(event));
                    }
                    return perWriter;
                }));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(perWriter);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(auditCount()).isEqualTo(before + (long) writers * perWriter);
        assertChainValid();
    }

    private static AuditEvent knownEvent() {
        return new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null, "SYSTEM",
                "TRANSFER", "TXN20261103A1B2C3D4",
                Map.<String, Object>of("status", "PENDING"),
                Map.<String, Object>of("status", "COMPLETED"),
                new BigDecimal("150000.00"), "VND", AuditOutcome.SUCCESS,
                "corr-audit-1", "req-audit-1", Map.<String, Object>of("ipMasked", "203.0.113.***"),
                KNOWN_OCCURRED_AT);
    }

    private static AuditEvent systemEvent(AuditAction action, String correlationId) {
        return new AuditEvent(action, AuditActorType.SYSTEM, null, "SYSTEM", "TRANSFER",
                "TXN-" + correlationId, null, Map.<String, Object>of("status", "COMPLETED"), null, null,
                AuditOutcome.SUCCESS, correlationId, null, null, Instant.now());
    }

    private void assertChainValid() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT audit_id, prev_hash, record_hash FROM banking.audit_log ORDER BY audit_id");
        assertThat(rows).isNotEmpty();
        String previousHash = AuditChain.GENESIS_PREV_HASH;
        for (Map<String, Object> row : rows) {
            String prevHash = ((String) row.get("prev_hash")).trim();
            String recordHash = ((String) row.get("record_hash")).trim();
            assertThat(prevHash)
                    .as("prev_hash of audit_id %s", row.get("audit_id"))
                    .isEqualTo(previousHash);
            assertThat(recordHash).matches("[0-9a-f]{64}");
            previousHash = recordHash;
        }
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM banking.audit_log", Long.class);
    }

    private long countByCorrelation(String correlationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM banking.audit_log WHERE correlation_id = ?", Long.class, correlationId);
    }
}
