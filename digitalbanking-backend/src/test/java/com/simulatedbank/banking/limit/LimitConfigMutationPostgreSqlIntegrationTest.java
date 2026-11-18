package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.ApprovedLimitChange;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.ConfigApplied;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.LimitChangeRequest;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.PendingChange;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.RejectedLimitChange;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 16 integration proof for the Limit mutation provider.
 *
 * <p>Uses the real Flyway schema (V1–V11), the production {@code JdbcTemplate} adapter and the
 * real Customer/Account public queries. Every assertion inspects committed database state, not
 * only the thrown error. Concurrency tests use two connections and a {@link CyclicBarrier} so the
 * applies contend on the scope/version locks instead of running sequentially.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Timeout(120)
class LimitConfigMutationPostgreSqlIntegrationTest {

    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final String CORRELATION = "limit-it-correlation";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("limit_mutation");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        // OI-API-05 policy bounds: high enough for normal cases, tight enough to prove the 422 path.
        registry.add("app.limit.policy.max-per-transaction", () -> "5000000");
        registry.add("app.limit.policy.max-daily", () -> "20000000");
    }

    @Autowired private LimitConfigMutationPort port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long maker;
    private long checker;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.limit_config");
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.app_user");
        });
        maker = staff("limit-it-maker");
        checker = staff("limit-it-checker");
    }

    // ---------------------------------------------------------------- successful lifecycle

    @Test
    void currentVersionIsZeroWithoutActiveConfigurationAndTracksTheAppliedVersion() {
        long customer = customer("limit-it-version");
        long approval = approval(maker, customer, null);

        assertThat(port.currentVersion(customer, null)).isZero();

        reserve(approval, maker, customer, null, "1000000", "5000000", 0);
        apply(approval, checker, 0);

        assertThat(port.currentVersion(customer, null)).isEqualTo(1L);
    }

    @Test
    void reserveCreatesPendingApprovalWithNextVersionAndAuditActor() {
        long customer = customer("limit-it-reserve");
        long approval = approval(maker, customer, null);

        PendingChange pending = reserve(approval, maker, customer, null, "1000000", "5000000", 0);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM banking.limit_config WHERE limit_id = ?", pending.limitConfigId());
        assertThat(pending.version()).isEqualTo(1L);
        assertThat(row)
                .containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("customer_id", customer)
                .containsEntry("account_id", null)
                .containsEntry("created_by", maker)
                .containsEntry("approval_id", approval)
                .containsEntry("approved_by", null)
                .containsEntry("currency", "VND");
        assertThat((Integer) row.get("version")).isEqualTo(1);
        assertThat((BigDecimal) row.get("per_transaction_limit")).isEqualByComparingTo("1000000.00");
        assertThat((BigDecimal) row.get("daily_limit")).isEqualByComparingTo("5000000.00");
        assertThat(row.get("effective_from")).isNotNull();
    }

    @Test
    void applyActivatesPendingAndSupersedesPreviousWithAuditVisiblePreviousValues() {
        long customer = customer("limit-it-apply");
        long firstApproval = approval(maker, customer, null);
        long firstConfig = reserve(firstApproval, maker, customer, null, "1000000", "5000000", 0)
                .limitConfigId();

        ConfigApplied first = apply(firstApproval, checker, 0);

        assertThat(first.limitConfigId()).isEqualTo(firstConfig);
        assertThat(first.version()).isEqualTo(1L);
        assertThat(first.previousPerTransactionLimit()).isNull();
        assertThat(first.previousDailyLimit()).isNull();
        assertThat(first.perTransactionLimit()).isEqualTo("1000000.00");
        assertThat(first.dailyLimit()).isEqualTo("5000000.00");

        long secondChecker = staff("limit-it-checker-2");
        long secondApproval = approval(maker, customer, null);
        reserve(secondApproval, maker, customer, null, "2000000", "10000000", 1);
        ConfigApplied second = apply(secondApproval, secondChecker, 1);

        assertThat(second.version()).isEqualTo(2L);
        assertThat(second.previousPerTransactionLimit()).isEqualTo("1000000.00");
        assertThat(second.previousDailyLimit()).isEqualTo("5000000.00");
        assertThat(second.perTransactionLimit()).isEqualTo("2000000.00");
        assertThat(second.dailyLimit()).isEqualTo("10000000.00");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM banking.limit_config WHERE customer_id = ? ORDER BY version", customer);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("status", "SUPERSEDED")
                .containsEntry("approved_by", checker);
        assertThat(rows.get(1))
                .containsEntry("status", "ACTIVE")
                .containsEntry("approved_by", secondChecker)
                .containsEntry("approval_id", secondApproval);
        assertThat(rows.get(1).get("approved_at")).isNotNull();
        assertThat((Integer) rows.get(1).get("version")).isEqualTo(2);
        assertThat(countActive(customer, null)).isEqualTo(1);
    }

    @Test
    void accountScopeIsIndependentFromTheCustomerScope() {
        long customer = customer("limit-it-scopes");
        long account = account(customer);

        long customerApproval = approval(maker, customer, null);
        reserve(customerApproval, maker, customer, null, "1000000", "5000000", 0);
        apply(customerApproval, checker, 0);

        assertThat(port.currentVersion(customer, null)).isEqualTo(1L);
        assertThat(port.currentVersion(customer, account)).isZero();

        long accountApproval = approval(maker, customer, account);
        reserve(accountApproval, maker, customer, account, "500000", "2000000", 0);
        apply(accountApproval, checker, 0);

        assertThat(port.currentVersion(customer, null)).isEqualTo(1L);
        assertThat(port.currentVersion(customer, account)).isEqualTo(1L);
        assertThat(countActive(customer, null)).isEqualTo(1);
        assertThat(countActive(customer, account)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- stale/duplicate/terminal

    @Test
    void reserveWithStaleExpectedVersionConflictsWithoutWriting() {
        long customer = customer("limit-it-stale-reserve");
        long applied = approval(maker, customer, null);
        reserve(applied, maker, customer, null, "1000000", "5000000", 0);
        apply(applied, checker, 0);

        long staleApproval = approval(maker, customer, null);

        assertCode(() -> reserve(staleApproval, maker, customer, null, "2000000", "10000000", 0),
                ErrorCode.ROLE_CONFLICT);
        assertThat(configCountForApproval(staleApproval)).isZero();
        assertThat(countActive(customer, null)).isEqualTo(1);
    }

    @Test
    void applyWithStaleExpectedVersionConflictsAndLeavesBothRowsUnchanged() {
        long customer = customer("limit-it-stale-apply");
        long firstApproval = approval(maker, customer, null);
        long activeId = reserve(firstApproval, maker, customer, null, "1000000", "5000000", 0)
                .limitConfigId();
        apply(firstApproval, checker, 0);

        long pendingApproval = approval(maker, customer, null);
        long pendingId = reserve(pendingApproval, maker, customer, null, "2000000", "10000000", 1)
                .limitConfigId();

        // Simulate another committed configuration change after the pending row was reserved.
        tx.executeWithoutResult(status ->
                jdbc.update("UPDATE banking.limit_config SET version = 9 WHERE limit_id = ?", activeId));

        assertCode(() -> apply(pendingApproval, checker, 1), ErrorCode.ROLE_CONFLICT);

        assertThat(status(pendingId)).isEqualTo("PENDING_APPROVAL");
        assertThat(version(pendingId)).isEqualTo(2);
        assertThat(status(activeId)).isEqualTo("ACTIVE");
        assertThat(version(activeId)).isEqualTo(9);
        assertThat(countActive(customer, null)).isEqualTo(1);
    }

    @Test
    void secondApplyOfTheSameApprovalIsAnInvalidStateTransition() {
        long customer = customer("limit-it-duplicate-apply");
        long approval = approval(maker, customer, null);
        long configId = reserve(approval, maker, customer, null, "1000000", "5000000", 0)
                .limitConfigId();
        apply(approval, checker, 0);

        assertCode(() -> apply(approval, checker, 0), ErrorCode.INVALID_STATE_TRANSITION);

        assertThat(configCountForApproval(approval)).isEqualTo(1);
        assertThat(status(configId)).isEqualTo("ACTIVE");
        assertThat(version(configId)).isEqualTo(1);
        assertThat(countActive(customer, null)).isEqualTo(1);
    }

    @Test
    void duplicateReserveForTheSameApprovalIsAnInvalidStateTransition() {
        long customer = customer("limit-it-duplicate-reserve");
        long approval = approval(maker, customer, null);
        reserve(approval, maker, customer, null, "1000000", "5000000", 0);

        assertCode(() -> reserve(approval, maker, customer, null, "2000000", "5000000", 0),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(configCountForApproval(approval)).isEqualTo(1);
    }

    @Test
    void rejectedPendingCannotBeApprovedLater() {
        long customer = customer("limit-it-reject");
        long approval = approval(maker, customer, null);
        long configId = reserve(approval, maker, customer, null, "1000000", "5000000", 0)
                .limitConfigId();

        RejectedLimitChange rejected = tx.execute(status -> port.rejectPendingChange(
                approval, checker, CORRELATION));

        assertThat(rejected).isEqualTo(new RejectedLimitChange(approval, configId));
        assertThat(status(configId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE limit_id = ? AND approved_by IS NULL AND approved_at IS NULL
                """, Integer.class, configId)).isEqualTo(1);

        assertCode(() -> apply(approval, checker, 0), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(status(configId)).isEqualTo("REJECTED");
        assertThat(countActive(customer, null)).isZero();
        assertThat(configCountForApproval(approval)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- validation and policy

    @Test
    void configuredPolicyBoundsRejectImpossibleProposalsWithoutWriting() {
        long customer = customer("limit-it-policy");

        long perTransactionApproval = approval(maker, customer, null);
        assertCode(() -> reserve(perTransactionApproval, maker, customer, null, "6000000", "10000000", 0),
                ErrorCode.LIMIT_OUT_OF_POLICY);
        assertThat(configCountForApproval(perTransactionApproval)).isZero();

        long dailyApproval = approval(maker, customer, null);
        assertCode(() -> reserve(dailyApproval, maker, customer, null, "1000000", "25000000", 0),
                ErrorCode.LIMIT_OUT_OF_POLICY);
        assertThat(configCountForApproval(dailyApproval)).isZero();
        assertThat(countActive(customer, null)).isZero();
    }

    @Test
    void unknownCustomerIsRejectedWithCustomerNotFoundAndNoRow() {
        long approval = approval(maker, Long.MAX_VALUE, null);

        assertCode(() -> reserve(approval, maker, Long.MAX_VALUE, null, "1000000", "5000000", 0),
                ErrorCode.CUSTOMER_NOT_FOUND);
        assertThat(configCountForApproval(approval)).isZero();
    }

    @Test
    void foreignAccountIsRejectedWithAuthForbiddenAndNoRow() {
        long owner = customer("limit-it-owner");
        long foreignAccount = account(owner);
        long requester = customer("limit-it-requester");
        long approval = approval(maker, requester, foreignAccount);

        assertCode(() -> reserve(approval, maker, requester, foreignAccount, "1000000", "5000000", 0),
                ErrorCode.AUTH_FORBIDDEN);
        assertThat(configCountForApproval(approval)).isZero();
    }

    @Test
    void missingAccountIsRejectedWithAccountNotFoundAndNoRow() {
        long customer = customer("limit-it-missing-account");
        long approval = approval(maker, customer, Long.MAX_VALUE);

        assertCode(() -> reserve(approval, maker, customer, Long.MAX_VALUE, "1000000", "5000000", 0),
                ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(configCountForApproval(approval)).isZero();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void concurrentAppliesForTheFirstVersionProduceExactlyOneActiveWinner() throws Exception {
        long customer = customer("limit-it-race-first");
        long approvalA = approval(maker, customer, null);
        long approvalB = approval(maker, customer, null);
        reserve(approvalA, maker, customer, null, "1000000", "5000000", 0);
        reserve(approvalB, maker, customer, null, "2000000", "5000000", 0);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> outcomes = race(
                applyAtBarrier(new ApprovedLimitChange(approvalA, checker, 0, CORRELATION), barrier),
                applyAtBarrier(new ApprovedLimitChange(approvalB, checker, 0, CORRELATION), barrier));

        assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", "ROLE_CONFLICT");
        assertThat(countActive(customer, null)).isEqualTo(1);
        Map<String, Object> winner = jdbc.queryForMap(
                "SELECT * FROM banking.limit_config WHERE customer_id = ? AND status = 'ACTIVE'", customer);
        assertThat((Integer) winner.get("version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND status = 'ACTIVE' AND version = 1
                """, Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND status = 'PENDING_APPROVAL'
                """, Integer.class, customer)).isEqualTo(1);
    }

    @Test
    void concurrentAppliesForAnExistingVersionProduceExactlyOneNextVersion() throws Exception {
        long customer = customer("limit-it-race-next");
        long baseApproval = approval(maker, customer, null);
        reserve(baseApproval, maker, customer, null, "1000000", "5000000", 0);
        apply(baseApproval, checker, 0);

        long approvalA = approval(maker, customer, null);
        long approvalB = approval(maker, customer, null);
        reserve(approvalA, maker, customer, null, "2000000", "10000000", 1);
        reserve(approvalB, maker, customer, null, "3000000", "15000000", 1);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> outcomes = race(
                applyAtBarrier(new ApprovedLimitChange(approvalA, checker, 1, CORRELATION), barrier),
                applyAtBarrier(new ApprovedLimitChange(approvalB, checker, 1, CORRELATION), barrier));

        assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", "ROLE_CONFLICT");
        assertThat(countActive(customer, null)).isEqualTo(1);
        Map<String, Object> active = jdbc.queryForMap(
                "SELECT * FROM banking.limit_config WHERE customer_id = ? AND status = 'ACTIVE'", customer);
        assertThat((Integer) active.get("version")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND status = 'SUPERSEDED' AND version = 1
                """, Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND status = 'PENDING_APPROVAL'
                """, Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND status = 'ACTIVE' AND version = 2
                """, Integer.class, customer)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private PendingChange reserve(long approvalId, long actor, long customerId, Long accountId,
            String perTransaction, String daily, long expectedVersion) {
        return tx.execute(status -> port.reservePendingChange(new LimitChangeRequest(
                approvalId, actor, customerId, accountId, new BigDecimal(perTransaction),
                new BigDecimal(daily), "VND", EFFECTIVE_FROM, expectedVersion)));
    }

    private ConfigApplied apply(long approvalId, long checkerId, long expectedVersion) {
        return tx.execute(status -> port.applyApprovedLimitChange(
                new ApprovedLimitChange(approvalId, checkerId, expectedVersion, CORRELATION)));
    }

    private Callable<String> applyAtBarrier(ApprovedLimitChange change, CyclicBarrier barrier) {
        return () -> {
            try {
                return tx.execute(status -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (Exception interrupted) {
                        throw new IllegalStateException("Barrier failure", interrupted);
                    }
                    port.applyApprovedLimitChange(change);
                    return "APPLIED";
                });
            } catch (BankingBusinessException rejection) {
                return rejection.code().name();
            }
        };
    }

    private static List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> one = pool.submit(first);
            Future<String> two = pool.submit(second);
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private long staff(String prefix) {
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type, status)
                VALUES (?, 'external-hash', 'STAFF', 'ACTIVE') RETURNING user_id
                """, Long.class, unique(prefix)));
    }

    private long customer(String prefix) {
        long userId = staff(prefix + "-user");
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.customer (user_id, full_name_enc)
                VALUES (?, '\\x00'::bytea) RETURNING customer_id
                """, Long.class, userId));
    }

    private long account(long customerId) {
        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.account (customer_id, account_no_enc, account_no_hash, account_no_last4)
                VALUES (?, '\\x00'::bytea, ?, '1234') RETURNING account_id
                """, Long.class, customerId, hash.substring(0, 64)));
    }

    private long approval(long makerId, long targetCustomerId, Long accountId) {
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, maker_id, correlation_id)
                VALUES ('LIMIT_CHANGE', ?, ?, '{}'::jsonb, ?, ?) RETURNING approval_id
                """, Long.class, accountId == null ? "CUSTOMER" : "ACCOUNT",
                Long.toString(accountId == null ? targetCustomerId : accountId), makerId,
                unique("limit-it-approval")));
    }

    private int countActive(long customerId, Long accountId) {
        if (accountId == null) {
            return jdbc.queryForObject("""
                    SELECT count(*) FROM banking.limit_config
                     WHERE customer_id = ? AND account_id IS NULL AND status = 'ACTIVE'
                    """, Integer.class, customerId);
        }
        return jdbc.queryForObject("""
                SELECT count(*) FROM banking.limit_config
                 WHERE customer_id = ? AND account_id = ? AND status = 'ACTIVE'
                """, Integer.class, customerId, accountId);
    }

    private int configCountForApproval(long approvalId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM banking.limit_config WHERE approval_id = ?", Integer.class, approvalId);
    }

    private String status(long limitConfigId) {
        return jdbc.queryForObject(
                "SELECT status FROM banking.limit_config WHERE limit_id = ?", String.class, limitConfigId);
    }

    private int version(long limitConfigId) {
        return jdbc.queryForObject(
                "SELECT version FROM banking.limit_config WHERE limit_id = ?", Integer.class, limitConfigId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }
}
