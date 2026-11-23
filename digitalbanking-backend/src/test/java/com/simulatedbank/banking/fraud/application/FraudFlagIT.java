package com.simulatedbank.banking.fraud.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.adapter.out.FraudJdbcRepository;
import com.simulatedbank.banking.fraud.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.simulatedbank.banking.support.IntegratedProviderReferences;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class FraudFlagIT {

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

    private static boolean schemaInitialized = false;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired FraudJdbcRepository repository;
    @Autowired FraudReviewService reviewService;

    private final Instant now = Instant.parse("2026-11-15T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        if (!schemaInitialized) {
            DriverManagerDataSource migratorDs = new DriverManagerDataSource();
            migratorDs.setUrl(postgres.getJdbcUrl());
            migratorDs.setUsername("banking_migrator");
            migratorDs.setPassword("test_migrator");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("fraud/fraud-schema.sql"));
            populator.execute(migratorDs);
            try (var connection = migratorDs.getConnection()) {
                IntegratedProviderReferences.seed(connection);
            }
            schemaInitialized = true;
        }

        transactions.executeWithoutResult(status -> {
            jdbc.execute("DELETE FROM banking.fraud_flag");
            jdbc.execute("DELETE FROM banking.fraud_rule_config");
        });
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("DELETE FROM banking.fraud_flag");
            jdbc.execute("DELETE FROM banking.fraud_rule_config");
        });
    }

    @Test
    @DisplayName("1. Composite foreign key constraint is enforced")
    void compositeForeignKeyEnforced() {
        // Insert valid rule config v1
        FraudRuleConfig config = new FraudRuleConfig(
                "LARGE_VAL", 1, "Large Value", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
        transactions.executeWithoutResult(status -> repository.insertRuleConfig(config));

        // Insert flag referencing valid rule config v1 -> Succeeds
        long flagId = transactions.execute(status ->
                repository.insertFlag(1001L, "LARGE_VAL", 1, Severity.HIGH,
                        Map.of("amount", "5000000", "currency", "VND"), "corr-1")
        );
        assertThat(flagId).isPositive();

        // Insert flag referencing non-existent rule version v2 -> Violates composite FK
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                repository.insertFlag(1002L, "LARGE_VAL", 2, Severity.HIGH,
                        Map.of("amount", "5000000"), "corr-2")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("2. Unique flag constraint per transfer, rule_code, and rule_version")
    void uniqueFlagPerTransferAndRuleVersion() {
        FraudRuleConfig config = new FraudRuleConfig(
                "LARGE_VAL", 1, "Large Value", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
        transactions.executeWithoutResult(status -> repository.insertRuleConfig(config));

        // First insert succeeds
        transactions.executeWithoutResult(status ->
                repository.insertFlag(2001L, "LARGE_VAL", 1, Severity.HIGH, Map.of("amount", "6000000"), "corr-a")
        );

        // Duplicate insert for same transfer, rule_code, rule_version violates unique constraint
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                repository.insertFlag(2001L, "LARGE_VAL", 1, Severity.HIGH, Map.of("amount", "6000000"), "corr-b")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("3. Concurrent review on same flag: one terminal winner, second gets conflict")
    void concurrentReviewOneTerminalWinner() throws Exception {
        FraudRuleConfig config = new FraudRuleConfig(
                "LARGE_VAL", 1, "Large Value", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
        transactions.executeWithoutResult(status -> repository.insertRuleConfig(config));

        long flagId = transactions.execute(status ->
                repository.insertFlag(3001L, "LARGE_VAL", 1, Severity.HIGH, Map.of("amount", "7000000"), "corr-3")
        );

        int numReviewers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numReviewers);
        CyclicBarrier barrier = new CyclicBarrier(numReviewers);
        List<FraudFlagItem> successes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Throwable> exceptions = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<Future<?>> futures = List.of(
                executor.submit(() -> {
                    try {
                        barrier.await();
                        FraudFlagItem item = reviewService.reviewFlag(flagId, ReviewDecision.ALLOW, "Reviewer 1 note", 901L);
                        successes.add(item);
                    } catch (Throwable t) {
                        exceptions.add(t);
                    }
                }),
                executor.submit(() -> {
                    try {
                        barrier.await();
                        FraudFlagItem item = reviewService.reviewFlag(flagId, ReviewDecision.BLOCK, "Reviewer 2 note", 902L);
                        successes.add(item);
                    } catch (Throwable t) {
                        exceptions.add(t);
                    }
                })
        );

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successes).hasSize(1);
        assertThat(exceptions).hasSize(1);

        Throwable conflictError = exceptions.get(0);
        assertThat(conflictError)
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code())
                        .isEqualTo(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED));

        FraudFlagItem finalFlag = repository.findFlagById(flagId).orElseThrow();
        assertThat(finalFlag.status().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("A held row lock triggers the current NOWAIT conflict mapping")
    void reviewWhileRowLockedMapsPostgresLockConflict() throws Exception {
        FraudRuleConfig config = new FraudRuleConfig(
                "LOCK_RULE", 1, "Lock Rule", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
        transactions.executeWithoutResult(status -> repository.insertRuleConfig(config));
        long flagId = transactions.execute(status -> repository.insertFlag(
                3002L, "LOCK_RULE", 1, Severity.HIGH, Map.of("amount", "7000000"), "corr-lock"));

        try (Connection lockConnection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), "banking_app", "test_app")) {
            lockConnection.setAutoCommit(false);
            try {
                try (var statement = lockConnection.prepareStatement(
                        "SELECT flag_id FROM banking.fraud_flag WHERE flag_id = ? FOR UPDATE")) {
                    statement.setLong(1, flagId);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }

                assertThatThrownBy(() -> reviewService.reviewFlag(flagId, ReviewDecision.ALLOW, "Valid note", 901L))
                        .isInstanceOf(BankingBusinessException.class)
                        .satisfies(ex -> {
                            assertThat(((BankingBusinessException) ex).code())
                                    .isEqualTo(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED);
                            Throwable cause = ex;
                            while (cause != null && !(cause instanceof java.sql.SQLException)) {
                                cause = cause.getCause();
                            }
                            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                            assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("55P03");
                        });
                assertThat(repository.findFlagById(flagId).orElseThrow().status()).isEqualTo(FlagStatus.OPEN);
            } finally {
                lockConnection.rollback();
            }
        }

        assertThat(reviewService.reviewFlag(flagId, ReviewDecision.ALLOW, "Valid note", 901L).status())
                .isEqualTo(FlagStatus.ALLOWED);
    }

    @Test
    @DisplayName("4. Savepoint isolation: error in optional evaluation rolls back savepoint, main transaction usable")
    void savepointIsolationRollsBackSubtransaction() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute((Connection connection) -> {
                // Main transaction inserts a valid business record
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("INSERT INTO audit_test.business_probe (id) VALUES (99999)");

                    // Create savepoint for optional evaluation
                    Savepoint sp = connection.setSavepoint("optional_eval_sp");
                    try {
                        // Injected intentional PostgreSQL error inside subtransaction
                        stmt.execute("SELECT * FROM non_existent_table_syntax_error");
                    } catch (Exception e) {
                        // Subtransaction aborted by PostgreSQL; rollback to savepoint!
                        connection.rollback(sp);
                    }

                    // Main transaction MUST remain healthy and usable!
                    // If savepoint isolation failed, this query would fail with 25P02 transaction aborted!
                    stmt.execute("INSERT INTO audit_test.business_probe (id) VALUES (99998)");
                }
                return null;
            });
        });

        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id IN (99999, 99998)", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("5. Direct insertFlag rejects unsafe matched_value at persistence boundary")
    void directInsertFlagRejectsUnsafeMatchedValue() {
        // 1. Unmasked account digits with spaces/hyphens
        assertThatThrownBy(() -> repository.insertFlag(
                4001L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "currency", "VND", "account", "1234 5678 9012"), "corr-p1"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 2. Email in value
        assertThatThrownBy(() -> repository.insertFlag(
                4002L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "currency", "VND", "ruleCode", "suspicious_user@bank.com"), "corr-p2"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 3. Phone in value
        assertThatThrownBy(() -> repository.insertFlag(
                4003L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "currency", "VND", "ruleCode", "+84912345678"), "corr-p3"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 4. Nested structures (Map or List)
        assertThatThrownBy(() -> repository.insertFlag(
                4004L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", Map.of("nested", "amount")), "corr-p4"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 5. Wrong type for numeric amount or count
        assertThatThrownBy(() -> repository.insertFlag(
                4005L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "not_a_number", "currency", "VND"), "corr-p5"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 6. Secret-shaped values
        assertThatThrownBy(() -> repository.insertFlag(
                4006L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "currency", "VND", "ruleCode", "token_otp_123456"), "corr-p6"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 7. Negative amount
        assertThatThrownBy(() -> repository.insertFlag(
                4007L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "-500000", "currency", "VND"), "corr-p7"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 8. Fractional count or negative count
        assertThatThrownBy(() -> repository.insertFlag(
                4008L, "HIGH_FREQ", 1, Severity.HIGH,
                Map.of("count", 3.14, "thresholdCount", 5, "windowSeconds", 600, "comparator", "GREATER_THAN"), "corr-p8"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        assertThatThrownBy(() -> repository.insertFlag(
                4009L, "HIGH_FREQ", 1, Severity.HIGH,
                Map.of("count", -1, "thresholdCount", 5, "windowSeconds", 600, "comparator", "GREATER_THAN"), "corr-p9"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 9. Invalid comparator (ensure raw value is not echoed)
        assertThatThrownBy(() -> repository.insertFlag(
                4010L, "HIGH_FREQ", 1, Severity.HIGH,
                Map.of("count", 10, "thresholdCount", 5, "windowSeconds", 600, "comparator", "EQUALS"), "corr-p10"
        )).satisfies(ex -> {
            assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class);
            assertThat(ex.getMessage()).doesNotContain("EQUALS");
        });

        // 10. Non-VND currency rejected
        assertThatThrownBy(() -> repository.insertFlag(
                4011L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "currency", "USD"), "corr-p11"
        )).satisfies(ex -> assertThat(ex).isInstanceOfAny(IllegalArgumentException.class, org.springframework.dao.InvalidDataAccessApiUsageException.class));

        // 11. Verify zero DB rows inserted for any of the failed calls
        Integer failedCount = jdbc.queryForObject(
                "SELECT count(*) FROM banking.fraud_flag WHERE transfer_id BETWEEN 4001 AND 4011", Integer.class);
        assertThat(failedCount).isZero();

        // 12. Valid large amount (10,000,000 VND) succeeds without false-positive account detection
        FraudRuleConfig config = new FraudRuleConfig(
                "LARGE_VAL", 1, "Large Value", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
        transactions.executeWithoutResult(status -> repository.insertRuleConfig(config));

        long validFlagId = transactions.execute(status -> repository.insertFlag(
                4012L, "LARGE_VAL", 1, Severity.HIGH,
                Map.of("amount", "10000000", "thresholdAmount", "5000000", "currency", "VND"), "corr-valid"
        ));
        assertThat(validFlagId).isPositive();
    }

    @Test
    @DisplayName("6. reviewFlag validates reviewerId format rejecting null, zero, or negative ID (does not verify authenticated identity or RBAC)")
    void reviewFlagRejectsInvalidReviewerId() {
        assertThatThrownBy(() -> reviewService.reviewFlag(100L, ReviewDecision.ALLOW, "note", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");

        assertThatThrownBy(() -> reviewService.reviewFlag(100L, ReviewDecision.ALLOW, "note", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");

        assertThatThrownBy(() -> reviewService.reviewFlag(100L, ReviewDecision.ALLOW, "note", -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");
    }
}
