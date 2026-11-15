package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.audit.application.AuditRecorder;
import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJdbcRepository;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitEnforcement;
import com.simulatedbank.banking.limit.api.LimitRules;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LimitEnforcementIT {
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
    }

    @Autowired LimitEnforcement enforcement;
    @Autowired LimitJdbcRepository repository;
    @Autowired LimitRules rules;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRecorder audit;

    @BeforeEach
    void reset() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE banking.limit_config,banking.limit_usage RESTART IDENTITY");
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE audit_test.business_probe");
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void completionAtEqualityUsesAllApplicableScopes(boolean customer, boolean account) {
        configure(customer, account, "100", "100");
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("100"));
            assertThat(check.outcome()).isEqualTo(LimitRules.CheckOutcome.PASS);
            assertThat(check.accountUsage() != null).isEqualTo(account);
            enforcement.recordCompleted(check);
        });
        assertUsage(null, "100", 1);
        if (account) assertUsage(21L, "100", 1);
        else assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isEqualTo(1);
    }

    @Test
    void notConfiguredNeverAuthorizesIncrement() {
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("10"));
            assertThat(check.outcome()).isEqualTo(LimitRules.CheckOutcome.NOT_CONFIGURED);
            assertThat(check.customerUsage().usedAmount()).isEqualByComparingTo("0");
        });
        assertUsage(null, "0", 0);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, amount("10")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertUsage(null, "0", 0);
    }

    @ParameterizedTest
    @CsvSource({"100,200,50,200,60", "50,200,100,200,60", "100,100,100,200,60", "100,200,100,100,60"})
    void eitherPerTransactionOrDailyScopeRejectsWithoutPoisoningCaller(
            String customerPer, String customerDaily, String accountPer, String accountDaily, String requested) {
        transactions.executeWithoutResult(tx -> {
            repository.insertConfiguration(config(null, customerPer, customerDaily));
            repository.insertConfiguration(config(21L, accountPer, accountDaily));
        });
        transactions.executeWithoutResult(tx -> {
            var first = enforcement.lockAndCheck(11, 21, amount("50"));
            enforcement.recordCompleted(first);
        });
        transactions.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> enforcement.lockAndCheck(11, 21, amount(requested)))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
        });
        assertThat(probeCount()).isEqualTo(1);
        assertUsage(null, "50", 1);
        assertUsage(21L, "50", 1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.001", "100000000000000000.00"})
    void invalidAmountDoesNotCreateUsage(String value) {
        transactions.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> enforcement.lockAndCheck(11, 21, value == null ? null : amount(value)))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
        });
        assertThat(probeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @Test
    void resolvesConfigurationAgainInsteadOfTrustingEarlyCheck() {
        configure(true, false, "100", "100");
        assertThat(rules.checkPerTransaction(11, 21, amount("60"), Instant.now()))
                .isEqualTo(LimitRules.CheckOutcome.PASS);
        transactions.executeWithoutResult(tx -> enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, amount("50"))));
        transactions.executeWithoutResult(tx -> assertThatThrownBy(() -> enforcement.lockAndCheck(11, 21, amount("60")))
                .isInstanceOf(BankingBusinessException.class));
        assertUsage(null, "50", 1);
    }

    @Test
    void businessRejectionAfterCheckDoesNotIncrement() {
        configure(true, true, "100", "100");
        transactions.executeWithoutResult(tx -> {
            enforcement.lockAndCheck(11, 21, amount("20"));
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
            // Caller chooses business FAILED (e.g. insufficient balance), so never calls recordCompleted.
        });
        assertThat(probeCount()).isEqualTo(1);
        assertUsage(null, "0", 0);
        assertUsage(21L, "0", 0);
    }

    @Test
    void callerFailureAfterIncrementRollsBackBothScopesAndBusinessWork() {
        configure(true, true, "100", "100");
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("20"));
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
            enforcement.recordCompleted(check);
            throw new IllegalStateException("injected technical failure after usage");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(probeCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @Test
    void realAuditFailureEvenWhenCaughtRollsBackUsageAndBusinessWork() {
        configure(true, true, "100", "100");
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("20"));
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
            enforcement.recordCompleted(check);
            var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 1L, "CUSTOMER", "TRANSFER", "E2-AUDIT",
                    null, Map.of("status", "COMPLETED"), amount("20"), "VND", "E2-CORR", "E2-REQ", "SUCCESS", null);
            try {
                audit.recordInTransaction(event, null);
            } catch (IllegalArgumentException expected) {
                // Real audit fail-closed marks caller rollback-only.
            }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(probeCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @Test
    void requiresCallerTransactionAndRejectsReadOnlyTransaction() {
        assertThatThrownBy(() -> enforcement.lockAndCheck(11, 21, amount("1")))
                .isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(transactions.getTransactionManager());
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(tx -> enforcement.lockAndCheck(11, 21, amount("1"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void handleCannotBeRepeatedOrReusedInAnotherTransaction() {
        configure(true, true, "100", "100");
        var checked = new AtomicReference<LimitEnforcement.Check>();
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("20"));
            checked.set(check);
            enforcement.recordCompleted(check);
        });
        assertThatThrownBy(() -> enforcement.recordCompleted(checked.get()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> enforcement.recordCompleted(checked.get())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("20"));
            enforcement.recordCompleted(check);
            try {
                enforcement.recordCompleted(check);
            } catch (IllegalArgumentException expected) { }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertUsage(null, "20", 1);
        assertUsage(21L, "20", 1);
    }

    @Test
    void unusedHandleIsRejectedFromAfterCommitBeforeSpringCleansTransactionContext() {
        configure(true, true, "100", "100");
        var rejected = new AtomicReference<Throwable>();
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("20"));
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                enforcement.recordCompleted(check);
                            } catch (RuntimeException failure) {
                                rejected.set(failure);
                            }
                        }
                    });
        });
        assertThat(rejected.get()).isInstanceOf(IllegalTransactionStateException.class);
        assertUsage(null, "0", 0);
        assertUsage(21L, "0", 0);
    }

    @Test
    void firstProviderCallFromAfterCommitRequiresPhysicalCallerTransaction() {
        configure(true, true, "100", "100");
        var rejected = new AtomicReference<Throwable>();
        transactions.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                enforcement.lockAndCheck(11, 21, amount("20"));
                            } catch (RuntimeException failure) {
                                rejected.set(failure);
                            }
                        }
                    });
        });
        assertThat(rejected.get()).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(probeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @Test
    void multipleChecksCannotOverspendWithinOneCallerTransaction() {
        configure(true, true, "100", "100");
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            var first = enforcement.lockAndCheck(11, 21, amount("60"));
            var second = enforcement.lockAndCheck(11, 21, amount("60"));
            enforcement.recordCompleted(first);
            try {
                enforcement.recordCompleted(second);
            } catch (org.springframework.dao.DataAccessException expected) { }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void twentyIndependentTransactionsNeverExceedCustomerDaily(boolean existingCounter, boolean differentAccounts) throws Exception {
        configure(true, true, "10", "100");
        transactions.executeWithoutResult(tx -> repository.insertConfiguration(config(22L, "10", "100")));
        if (existingCounter) transactions.executeWithoutResult(tx -> enforcement.lockAndCheck(11, 21, amount("1")));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 20).mapToObj(i -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return transactions.execute(tx -> {
                    try {
                        enforcement.recordCompleted(enforcement.lockAndCheck(11, !differentAccounts || i % 2 == 0 ? 21 : 22, amount("10")));
                        return true;
                    } catch (BankingBusinessException rejected) {
                        assertThat(rejected.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                        return false;
                    }
                });
            })).toList();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(10);
        }
        assertUsage(null, "100", 10);
        var total = jdbc.queryForMap("SELECT sum(used_amount) AS amount, sum(transfer_count) AS count FROM banking.limit_usage WHERE account_id IS NOT NULL");
        assertThat((BigDecimal) total.get("amount")).isEqualByComparingTo("100");
        assertThat(((Number) total.get("count")).intValue()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isEqualTo(differentAccounts ? 3 : 2);
    }

    @Test
    void checkHoldsCustomerLockUntilCallerCommitAfterIncrement() throws Exception {
        configure(true, true, "100", "100");
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                var check = enforcement.lockAndCheck(11, 21, amount("60"));
                checked.countDown();
                await(release);
                enforcement.recordCompleted(check);
            }));
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                secondStarted.countDown();
                assertThatThrownBy(() -> enforcement.lockAndCheck(11, 22, amount("60")))
                        .isInstanceOf(BankingBusinessException.class);
            }));
            try {
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class))
                                .isPositive());
                assertThat(second.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertUsage(null, "60", 1);
    }

    @Test
    void appRoleCanOnlyUpdateUsageCountersNotScopeOrConfiguration() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("banking_app");
        assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user, 'banking.limit_usage', 'used_amount', 'UPDATE')", Boolean.class)).isTrue();
        for (String column : new String[]{"usage_id", "customer_id", "account_id", "usage_date", "currency"}) {
            assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user, 'banking.limit_usage', ?, 'UPDATE')", Boolean.class, column)).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'banking.limit_config', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'banking.limit_usage', 'DELETE,TRUNCATE')", Boolean.class)).isFalse();
    }

    @Test
    void finalCheckRefreshesConfigurationAfterEarlyPerTransactionPass() throws Exception {
        configure(true, false, "100", "100");
        assertThat(rules.checkPerTransaction(11, 21, amount("60"), Instant.now()))
                .isEqualTo(LimitRules.CheckOutcome.PASS);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("UPDATE banking.limit_config SET per_transaction_limit=50");
        }
        transactions.executeWithoutResult(tx -> assertThatThrownBy(() -> enforcement.lockAndCheck(11, 21, amount("60")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED)));
        assertUsage(null, "0", 0);
    }

    @Test
    void configSnapshotRemainsStableBetweenCheckAndCompletion() throws Exception {
        configure(true, true, "100", "100");
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("60"));
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
                 var statement = connection.createStatement()) {
                statement.execute("UPDATE banking.limit_config SET per_transaction_limit=50,daily_limit=50");
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
            enforcement.recordCompleted(check);
        });
        assertUsage(null, "60", 1);
        assertUsage(21L, "60", 1);
    }

    @Test
    void rejectsSnapshotIsolationThatCouldReadStaleUsageAfterWaiting() {
        var repeatable = new TransactionTemplate(transactions.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.executeWithoutResult(tx -> enforcement.lockAndCheck(11, 21, amount("1"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    @Test
    void expiredUnrecordedForeignAndSuspendedTransactionHandlesAreRejected() {
        configure(true, false, "100", "100");
        var expired = transactions.execute(tx -> enforcement.lockAndCheck(11, 21, amount("1")));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> enforcement.recordCompleted(expired)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                enforcement.recordCompleted(org.mockito.Mockito.mock(LimitEnforcement.Check.class))))
                .isInstanceOf(IllegalArgumentException.class);
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, amount("1"));
            var separate = new TransactionTemplate(transactions.getTransactionManager());
            separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThatThrownBy(() -> separate.executeWithoutResult(inner -> enforcement.recordCompleted(check)))
                    .isInstanceOf(IllegalArgumentException.class);
            enforcement.recordCompleted(check);
        });
        assertUsage(null, "1", 1);
    }

    @Test
    void accountCounterFromAnotherCustomerFailsClosedWithoutAccounting() throws Exception {
        configure(false, true, "100", "100");
        transactions.executeWithoutResult(tx -> jdbc.update(
                "INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES(12,21,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> enforcement.lockAndCheck(11, 21, amount("1"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage WHERE customer_id=11", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT used_amount FROM banking.limit_usage WHERE customer_id=12", BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    @Test
    void realAuditDatabaseInsertFailureRollsBackPreviouslyExistingUsage() throws Exception {
        configure(true, true, "100", "100");
        transactions.executeWithoutResult(tx -> enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, amount("10"))));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION banking.fail_e2_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'injected audit persistence failure'; END; $$;
                    CREATE TRIGGER fail_e2_audit BEFORE INSERT ON banking.audit_log
                    FOR EACH ROW EXECUTE FUNCTION banking.fail_e2_audit();
                    """);
            try {
                assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                    var check = enforcement.lockAndCheck(11, 21, amount("20"));
                    jdbc.update("INSERT INTO audit_test.business_probe(id) VALUES (1)");
                    enforcement.recordCompleted(check);
                    var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "E2-AUDIT-SQL",
                            Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), amount("20"), "VND",
                            "E2-CORR", "E2-REQ", "SUCCESS", null);
                    try {
                        audit.recordInTransaction(event, Instant.now());
                    } catch (org.springframework.dao.DataAccessException expected) { }
                })).isInstanceOf(UnexpectedRollbackException.class);
            } finally {
                statement.execute("DROP TRIGGER fail_e2_audit ON banking.audit_log; DROP FUNCTION banking.fail_e2_audit()");
            }
        }
        assertThat(probeCount()).isZero();
        assertUsage(null, "10", 1);
        assertUsage(21L, "10", 1);
    }

    private void configure(boolean customer, boolean account, String per, String daily) {
        transactions.executeWithoutResult(tx -> {
            if (customer) repository.insertConfiguration(config(null, per, daily));
            if (account) repository.insertConfiguration(config(21L, per, daily));
        });
    }

    private static LimitConfiguration config(Long account, String per, String daily) {
        return new LimitConfiguration(null, 11, account, amount(per), amount(daily), "VND",
                Instant.parse("2020-01-01T00:00:00Z"), null, LimitConfiguration.Status.ACTIVE, 1,
                1, 2L, null, null, Instant.parse("2020-01-01T00:00:00Z"), null);
    }

    private void assertUsage(Long account, String expected, int count) {
        var usages = jdbc.query("SELECT * FROM banking.limit_usage WHERE customer_id=11 AND account_id IS NOT DISTINCT FROM ?::bigint",
                (rs, row) -> Map.entry(rs.getBigDecimal("used_amount"), rs.getInt("transfer_count")), account);
        assertThat(usages).hasSize(1);
        assertThat(usages.getFirst().getKey()).isEqualByComparingTo(expected);
        assertThat(usages.getFirst().getValue()).isEqualTo(count);
    }

    private int probeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe", Integer.class);
    }

    private static BigDecimal amount(String value) { return new BigDecimal(value); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting test latch");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
