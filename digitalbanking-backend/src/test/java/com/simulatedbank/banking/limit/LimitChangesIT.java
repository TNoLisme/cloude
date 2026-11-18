package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;

import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.support.SqlAuditFixture;
import com.simulatedbank.banking.support.IntegratedLimitReferences;
import org.springframework.context.annotation.Import;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter;
import com.simulatedbank.banking.limit.api.LimitEnforcement;
import com.simulatedbank.banking.limit.api.LimitRules;
import com.simulatedbank.banking.limit.api.LimitChanges;
import com.simulatedbank.banking.limit.api.LimitChanges.Proposal;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.simulatedbank.banking.support.SqlFixture;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import({SqlAuditFixture.class, SqlFixture.class})
@ActiveProfiles("test")
@Testcontainers
class LimitChangesIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
        registry.add("app.limit.policy.max-per-transaction-vnd", () -> "1000");
        registry.add("app.limit.policy.max-daily-vnd", () -> "2000");
    }

    @Autowired LimitChanges changes;
    @Autowired TransactionTemplate transactions;
    @Autowired SqlFixture jdbc;
    @Autowired LimitRules rules;
    @Autowired LimitEnforcement enforcement;
    @Autowired AuditModuleApi audit;
    @Autowired ZoneId businessZoneId;
    @MockitoSpyBean LimitJpaAdapter repository;

    @BeforeEach
    void reset() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE banking.limit_config, banking.limit_usage RESTART IDENTITY");
            IntegratedLimitReferences.seed(connection, true);
        }
    }

    @Test
    void validProposalIsPending() {
        var result = prepare(81, null, "100", "200");
        assertThat(result.status()).isEqualTo(LimitConfiguration.Status.PENDING_APPROVAL);
        assertThat(result.approvalId()).isEqualTo(81L);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.approvedBy()).isNull();
        assertThat(result.approvedAt()).isNull();
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.effectiveFrom()).isNotNull();
        assertThat(rules.resolve(11, 21, Instant.now()).items()).isEmpty();
        assertThat(usage()).isEmpty();
    }

    @Test
    void pendingDoesNotChangeActiveOrUsageAndRejectKeepsHistory() {
        var active = activate(81, null, "100", "200");
        complete("50");
        var before = usage();
        var pending = prepare(82, null, "200", "300");
        assertThat(pending.version()).isEqualTo(2);
        assertThat(rules.resolve(11, 21, Instant.now()).items()).containsExactly(active);
        assertThat(usage()).isEqualTo(before);
        var rejected = transactions.execute(tx -> changes.reject(82, 2));
        assertThat(rejected.status()).isEqualTo(LimitConfiguration.Status.REJECTED);
        assertThat(rejected.approvedBy()).isNull();
        assertThat(rejected.approvedAt()).isNull();
        assertThat(repository.findConfiguration(active.limitId())).contains(active);
        assertThat(usage()).isEqualTo(before);
        var next = prepare(83, null, "100", "200");
        assertThat(next.version()).isEqualTo(3);
    }

    @Test
    void approvalSupersedesOldVersionAndLoweringPreservesUsageAndBlocksNextSpend() {
        var old = activate(81, null, "100", "200");
        complete("90");
        var before = usage();
        var pending = prepare(82, null, "50", "60");
        var active = transactions.execute(tx -> changes.approve(82, 2));
        assertThat(active.limitId()).isEqualTo(pending.limitId());
        assertThat(active.createdBy()).isEqualTo(pending.createdBy());
        assertThat(active.createdAt()).isEqualTo(pending.createdAt());
        assertThat(active.approvedBy()).isEqualTo(2L);
        assertThat(active.approvedAt()).isEqualTo(active.effectiveFrom());
        var history = repository.findConfiguration(old.limitId()).orElseThrow();
        assertThat(history.status()).isEqualTo(LimitConfiguration.Status.SUPERSEDED);
        assertThat(history.effectiveTo()).isEqualTo(active.effectiveFrom());
        assertThat(history.perTransactionLimit()).isEqualByComparingTo(old.perTransactionLimit());
        assertThat(rules.resolve(11, 21, Instant.now()).items()).containsExactly(active);
        assertThat(usage()).isEqualTo(before);
        failure(() -> complete("1"), ErrorCode.LIMIT_EXCEEDED);
        assertThat(usage()).isEqualTo(before);
    }

    @Test
    void duplicateApprovalCannotCreateAnotherProposalEvenInDifferentScope() {
        var pending = prepare(81, null, "100", "200");
        failure(() -> prepare(81, 21L, "100", "200"), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(repository.findConfiguration(pending.limitId())).contains(pending);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isEqualTo(1);
        assertThat(usage()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selfDecisionUnknownAndAlreadyDecidedFailWithoutChanges(boolean approve) {
        var pending = prepare(81, null, "100", "200");
        failure(() -> decide(81, 1, approve), ErrorCode.ROLE_CONFLICT);
        failure(() -> decide(999, 2, approve), ErrorCode.APPROVAL_NOT_FOUND);
        assertThat(repository.findConfiguration(pending.limitId())).contains(pending);
        decide(81, 2, approve);
        var after = configs();
        failure(() -> decide(81, 3, true), ErrorCode.APPROVAL_ALREADY_DECIDED);
        failure(() -> decide(81, 3, false), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(configs()).isEqualTo(after);
    }

    @ParameterizedTest
    @CsvSource({"0,100", "-1,100", "100,0", "100,99", "0.001,100", "100000000000000000,100000000000000000"})
    void invalidAmountsLeaveNoData(String per, String daily) {
        failure(() -> prepare(81, null, per, daily), ErrorCode.VALIDATION_ERROR);
        assertThat(configs()).isEmpty();
        assertThat(usage()).isEmpty();
    }

    @Test
    void invalidIdsCurrencyNullAndFutureAreRejected() {
        var now = Instant.now();
        for (var proposal : List.of(
                new Proposal(0, null, money("10"), money("20"), "VND", null, 1, 81),
                new Proposal(11, 0L, money("10"), money("20"), "VND", null, 1, 81),
                new Proposal(11, null, money("10"), money("20"), "VND", null, 0, 81),
                new Proposal(11, null, money("10"), money("20"), "VND", null, 1, 0),
                new Proposal(11, null, money("10"), money("20"), "USD", null, 1, 81),
                new Proposal(11, null, null, money("20"), "VND", null, 1, 81),
                new Proposal(11, null, money("10"), money("20"), "VND", now.plusSeconds(3600), 1, 81))) {
            failure(() -> transactions.execute(tx -> changes.prepare(proposal)), ErrorCode.VALIDATION_ERROR);
        }
        failure(() -> transactions.execute(tx -> changes.prepare(null)), ErrorCode.VALIDATION_ERROR);
        assertThat(configs()).isEmpty();
        assertThat(usage()).isEmpty();
    }

    @Test
    void outOfPolicyRejectedAtPreparationAndPolicyIsRecheckedBeforeApproval() {
        failure(() -> prepare(81, null, "1000.01", "2000"), ErrorCode.LIMIT_OUT_OF_POLICY);
        failure(() -> prepare(81, null, "1000", "2000.01"), ErrorCode.LIMIT_OUT_OF_POLICY);
        var pending = prepare(81, null, "1000", "2000");
        var source = new java.util.HashMap<String, Object>();
        source.put("app.limit.policy.max-per-transaction-vnd", "500");
        var environment = (org.springframework.core.env.ConfigurableEnvironment) contextEnvironment;
        var name = "changed-limit-policy";
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(name, source));
        try {
            failure(() -> decide(81, 2, true), ErrorCode.LIMIT_OUT_OF_POLICY);
            assertThat(repository.findConfiguration(pending.limitId())).contains(pending);
            source.put("app.limit.policy.max-per-transaction-vnd", "");
            failure(() -> decide(81, 2, true), ErrorCode.SERVICE_UNAVAILABLE);
            failure(() -> prepare(82, null, "1", "2"), ErrorCode.SERVICE_UNAVAILABLE);
            decide(81, 2, false);
        } finally {
            environment.getPropertySources().remove(name);
        }
        assertThat(usage()).isEmpty();
    }

    @Autowired org.springframework.core.env.Environment contextEnvironment;

    @Test
    void futurePendingVersionCannotBeApprovedEvenIfSeededOutsideProvider() {
        var pending = prepare(81, null, "100", "200");
        transactions.executeWithoutResult(tx -> jdbc.update(
                "UPDATE banking.limit_config SET effective_from = now() + interval '1 day' WHERE limit_id=?", pending.limitId()));
        failure(() -> decide(81, 2, true), ErrorCode.VALIDATION_ERROR);
        assertThat(repository.findConfiguration(pending.limitId()).orElseThrow().status())
                .isEqualTo(LimitConfiguration.Status.PENDING_APPROVAL);
        assertThat(usage()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void callerFailureRollsBackPrepareAndDecision(boolean approval) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            changes.prepare(proposal(81, null, "100", "200"));
            throw new IllegalStateException("caller failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(configs()).isEmpty();
        activate(81, null, "100", "200");
        prepare(82, null, "50", "100");
        var before = configs();
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            if (approval) changes.approve(82, 2); else changes.reject(82, 2);
            throw new IllegalStateException("caller failed after decision");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(configs()).isEqualTo(before);
        assertThat(usage()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "APPROVE", "REJECT"})
    void caughtAuditContractDoubleFailureRollsBackEveryMutation(String operation) {
        if (!operation.equals("PREPARE")) prepare(81, null, "100", "200");
        var before = configs();
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            switch (operation) {
                case "PREPARE" -> changes.prepare(proposal(81, null, "100", "200"));
                case "APPROVE" -> changes.approve(81, 2);
                default -> changes.reject(81, 2);
            }
            try {
                audit.recordInTransaction(null);
            } catch (IllegalArgumentException expected) {
                // The audit contract double marks the caller rollback-only even when the caller catches it.
            }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(configs()).isEqualTo(before);
        assertThat(usage()).isEmpty();
    }

    @Test
    void auditFixtureCommitAndSqlFailureParticipateInCallerTransaction() {
        var result = transactions.execute(tx -> {
            var config = changes.prepare(proposal(81, null, "100", "200"));
            audit.recordInTransaction(auditEvent("LIMIT_CHANGE_REQUESTED"));
            return config;
        });
        assertThat(repository.findConfiguration(result.limitId())).contains(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM limit_test.audit_event WHERE action='LIMIT_CHANGE_REQUESTED'", Integer.class))
                .isPositive();
        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                changes.approve(81, 2);
                // Trigger the test audit sink's database path by revoking INSERT.
                try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
                     var statement = connection.createStatement()) {
                    statement.execute("REVOKE INSERT ON limit_test.audit_event FROM banking_app");
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                audit.recordInTransaction(auditEvent("LIMIT_CHANGE_APPROVED"));
            })).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().extracting("SQLState").isEqualTo("42501");
        } finally {
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
                 var statement = connection.createStatement()) {
                statement.execute("GRANT INSERT ON limit_test.audit_event TO banking_app");
            } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        }
        assertThat(repository.findConfiguration(result.limitId())).contains(result);
    }

    @Test
    void concurrentPreparationsAndDecisionsPreserveVersionsAndOneActiveScope() throws Exception {
        var prepared = race(() -> prepare(81, null, "100", "200").version(),
                () -> prepare(82, null, "100", "200").version());
        assertThat(prepared).containsExactlyInAnyOrder(1, 2);
        var decisions = race(() -> decisionOutcome(81, true), () -> decisionOutcome(81, false));
        assertThat(decisions).containsExactlyInAnyOrder("SUCCESS", "APPROVAL_ALREADY_DECIDED");
        // A different pending version can be applied afterward; all versions remain present.
        decide(82, 2, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config WHERE status='ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(configs()).hasSize(2);
        assertThat(usage()).isEmpty();
    }

    @Test
    void concurrentFirstApprovalsForDifferentProposalsLeaveOneActiveAndHistory() throws Exception {
        prepare(81, null, "100", "200");
        prepare(82, null, "100", "200");
        assertThat(race(() -> decisionOutcome(81, true), () -> decisionOutcome(82, true)))
                .containsExactly("SUCCESS", "SUCCESS");
        assertThat(jdbc.queryForList("SELECT status FROM banking.limit_config", String.class))
                .containsExactlyInAnyOrder("ACTIVE", "SUPERSEDED");
        assertThat(usage()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"50,1", "0,1", "50,0"})
    void firstAccountScopeIsBlockedAfterUsageAndCanBeApprovedNextUnusedDay(String used, int count) {
        activate(81, null, "100", "200");
        transactions.executeWithoutResult(tx -> repository.insertUsage(new com.simulatedbank.banking.limit.api.LimitUsage(
                null, 11, null, LocalDate.now(businessZoneId), "VND", money(used), count, null)));
        var pending = prepare(82, 21L, "80", "100");
        var before = usage();
        failure(() -> decide(82, 2, true), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(repository.findConfiguration(pending.limitId())).contains(pending);
        assertThat(usage()).isEqualTo(before);
        var tomorrow = LocalDate.now(businessZoneId).plusDays(1).atStartOfDay(businessZoneId).toInstant();
        doReturn(tomorrow).when(org.springframework.test.util.AopTestUtils.<LimitJpaAdapter>getUltimateTargetObject(repository)).currentTime();
        var active = transactions.execute(tx -> changes.approve(82, 2));
        assertThat(active.effectiveFrom()).isEqualTo(tomorrow);
        assertThat(rules.resolve(11, 21, tomorrow).items()).extracting(LimitConfiguration::accountId).contains(null, 21L);
        assertThat(usage()).isEqualTo(before);
    }

    @Test
    void historicalExpiredAccountTrackingDoesNotBypassUsedDayGuard() {
        activate(81, null, "100", "200");
        var account = activate(82, 21L, "100", "200");
        transactions.executeWithoutResult(tx -> jdbc.update(
                "UPDATE banking.limit_config SET effective_from=now()-interval '2 days', effective_to=now()-interval '1 day' WHERE limit_id=?", account.limitId()));
        complete("50");
        prepare(83, 21L, "100", "200");
        failure(() -> decide(83, 2, true), ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void accountOnlyFirstConfigurationAppliesAndStoredScopeCannotBeReassigned() {
        var account = activate(81, 21L, "100", "200");
        assertThat(rules.resolve(11, 21, Instant.now()).items()).containsExactly(account);
        failure(() -> transactions.execute(tx -> changes.prepare(new Proposal(
                12, 21L, money("50"), money("100"), "VND", null, 1, 82))), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(usage()).isEmpty();
        complete("50");
        assertThat(usage()).hasSize(2);
    }

    @Test
    void alreadyTrackedAccountCanLowerItsLimitWithoutResettingCounters() {
        activate(81, null, "100", "200");
        activate(82, 21L, "100", "200");
        complete("50");
        var before = usage();
        prepare(83, 21L, "10", "20");
        decide(83, 2, true);
        assertThat(usage()).isEqualTo(before);
        failure(() -> complete("1"), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void completingEnforcementBlocksFirstAccountActivationAndGuardSeesCommittedUsage() throws Exception {
        activate(81, null, "100", "200");
        prepare(82, 21L, "100", "200");
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var transfer = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, money("10")));
                holding.countDown();
                waitFor(release);
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            var approval = workers.submit(() -> decisionOutcome(82, true));
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event='advisory' AND datname=current_database()", Integer.class)).isPositive());
                assertThat(approval.isDone()).isFalse();
            } finally { release.countDown(); }
            transfer.get(10, TimeUnit.SECONDS);
            assertThat(approval.get(10, TimeUnit.SECONDS)).isEqualTo("INVALID_STATE_TRANSITION");
        }
        assertThat(jdbc.queryForList("SELECT used_amount FROM banking.limit_usage", BigDecimal.class)).containsExactly(money("10.00"));
    }

    @Test
    void transactionStartedBeforeActivationCannotSkipNewAccountScope() throws Exception {
        activate(81, null, "100", "200");
        prepare(82, 21L, "1", "1");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var earlierTransaction = worker.submit(() -> transactions.execute(tx -> {
                var asOf = repository.transactionStart();
                started.countDown();
                waitFor(release);
                var check = enforcement.lockAndCheck(11, 21, money("10"));
                assertThat(check.asOf()).isEqualTo(asOf);
                return check.outcome();
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try { decide(82, 2, true); }
            finally { release.countDown(); }
            assertThat(earlierTransaction.get(10, TimeUnit.SECONDS)).isEqualTo(LimitRules.CheckOutcome.NOT_CONFIGURED);
        }
        assertThat(jdbc.queryForList("SELECT used_amount FROM banking.limit_usage", BigDecimal.class))
                .allSatisfy(amount -> assertThat(amount).isZero());
        failure(() -> complete("10"), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void missingReadOnlyAndCompletedCallerTransactionsCannotMutate() {
        assertThatThrownBy(() -> changes.prepare(proposal(81, null, "100", "200")))
                .isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(transactions.getTransactionManager());
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(tx -> changes.prepare(proposal(81, null, "100", "200"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(tx -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                assertThatThrownBy(() -> changes.prepare(proposal(81, null, "100", "200")))
                        .isInstanceOf(IllegalTransactionStateException.class);
            }
        }));
        var repeatable = new TransactionTemplate(transactions.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(tx -> changes.prepare(proposal(81, null, "100", "200"))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(configs()).isEmpty();
        assertThat(usage()).isEmpty();
    }

    @Test
    void lifecycleGrantsDoNotAllowChangingImmutableVersionOrDeletingHistory() {
        prepare(81, null, "100", "200");
        for (var column : List.of("customer_id", "account_id", "per_transaction_limit", "daily_limit", "currency", "version", "created_by", "approval_id", "created_at")) {
            assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user,'banking.limit_config',?,'UPDATE')", Boolean.class, column)).isFalse();
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM banking.limit_config")).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user,'banking.limit_config','status','UPDATE')", Boolean.class)).isTrue();
    }

    private LimitConfiguration prepare(long approval, Long account, String per, String daily) {
        return transactions.execute(tx -> changes.prepare(proposal(approval, account, per, daily)));
    }

    private LimitConfiguration activate(long approval, Long account, String per, String daily) {
        prepare(approval, account, per, daily);
        return transactions.execute(tx -> changes.approve(approval, 2));
    }

    private Proposal proposal(long approval, Long account, String per, String daily) {
        return new Proposal(11, account, money(per), money(daily), null, null, 1, approval);
    }

    private void decide(long approval, long checker, boolean approve) {
        transactions.executeWithoutResult(tx -> {
            if (approve) changes.approve(approval, checker); else changes.reject(approval, checker);
        });
    }

    private String decisionOutcome(long approval, boolean approve) {
        try { decide(approval, 2, approve); return "SUCCESS"; }
        catch (BankingBusinessException rejected) { return rejected.code().name(); }
    }

    private void complete(String amount) {
        transactions.executeWithoutResult(tx -> enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, money(amount))));
    }

    private List<Map<String, Object>> usage() { return jdbc.queryForList("SELECT * FROM banking.limit_usage ORDER BY usage_id"); }
    private List<Map<String, Object>> configs() { return jdbc.queryForList("SELECT * FROM banking.limit_config ORDER BY limit_id"); }
    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static void failure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    private static <T> List<T> race(Supplier<T> first, Supplier<T> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> { waitFor(start); return first.get(); });
            var right = workers.submit(() -> { waitFor(start); return second.get(); });
            start.countDown();
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("worker timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static com.simulatedbank.banking.audit.api.AuditEvent auditEvent(String action) {
        return SqlAuditFixture.approvalEvent(AuditAction.valueOf(action), "limit-e4-correlation");
    }
}
