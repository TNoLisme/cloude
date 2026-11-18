package com.simulatedbank.banking.admin.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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

/** Real PostgreSQL/Flyway/JPA evidence for the Admin approval persistence contract. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApprovalRequestPostgreSqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_approval");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
    }

    @Autowired private ApprovalRequestStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long secondChecker;
    private long target;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.app_user WHERE username LIKE 'adm-it-%'");
            maker = user("adm-it-maker-" + suffix);
            checker = user("adm-it-checker-" + suffix);
            secondChecker = user("adm-it-checker2-" + suffix);
            target = user("adm-it-target-" + suffix);
        });
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.app_user WHERE username LIKE 'adm-it-%'");
        });
    }

    @Test
    void rolePayloadRoundTripsThroughJsonb() {
        ApprovalRequest inserted = tx.execute(status -> store.insert(ApprovalRequest.propose(
                new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, target, null, "CHECKER", 7L,
                        "temporary checker"),
                maker, "corr-role", NOW)));

        assertThat(inserted).isNotNull();
        assertThat(inserted.approvalId()).isPositive();
        assertThat(inserted.requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(inserted.targetType()).isEqualTo(ApprovalTargetType.APP_USER);
        assertThat(inserted.status()).isEqualTo(ApprovalStatus.PENDING);

        ApprovalRequest loaded = store.findById(inserted.approvalId()).orElseThrow();
        RoleChangePayload payload = (RoleChangePayload) loaded.payload();
        assertThat(payload.operation()).isEqualTo(RoleChangeOperation.GRANT_PRIVILEGE);
        assertThat(payload.targetUserId()).isEqualTo(target);
        assertThat(payload.privilegeCode()).isEqualTo("CHECKER");
        assertThat(payload.expectedVersion()).isEqualTo(7L);
        assertThat(payload.reason()).isEqualTo("temporary checker");
        assertThat(jdbc.queryForObject("SELECT pg_typeof(payload)::text FROM banking.approval_request "
                + "WHERE approval_id=?", String.class, inserted.approvalId())).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id=?",
                String.class, inserted.approvalId())).isEqualTo("PENDING");
    }

    @Test
    void limitPayloadRoundTripsAndMapsAccountTarget() {
        ApprovalRequest inserted = tx.execute(status -> store.insert(ApprovalRequest.propose(
                new LimitChangePayload(2001L, 3001L, new BigDecimal("5000000.00"),
                        new BigDecimal("20000000.00"), "VND", NOW.plusSeconds(3600), " seasonal limit "),
                maker, "corr-limit", NOW)));

        ApprovalRequest loaded = store.findById(inserted.approvalId()).orElseThrow();
        LimitChangePayload payload = (LimitChangePayload) loaded.payload();
        assertThat(loaded.targetType()).isEqualTo(ApprovalTargetType.ACCOUNT);
        assertThat(loaded.targetId()).isEqualTo("3001");
        assertThat(payload.customerId()).isEqualTo(2001L);
        assertThat(payload.accountId()).isEqualTo(3001L);
        assertThat(payload.perTransactionLimit()).isEqualByComparingTo("5000000.00");
        assertThat(payload.dailyLimit()).isEqualByComparingTo("20000000.00");
        assertThat(payload.effectiveFrom()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(payload.reason()).isEqualTo("seasonal limit");
    }

    @Test
    void approveCommitsAppliedAndDuplicateDecisionIsRejected() {
        ApprovalRequest inserted = tx.execute(status -> store.insert(limitRequest()));

        tx.executeWithoutResult(status -> {
            ApprovalRequest locked = store.lockById(inserted.approvalId()).orElseThrow();
            locked.approve(checker, "approved", NOW.plusSeconds(60));
            store.update(locked);
        });

        ApprovalRequest applied = store.findById(inserted.approvalId()).orElseThrow();
        assertThat(applied.status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(applied.checkerId()).isEqualTo(checker);
        assertThat(applied.appliedAt()).isEqualTo(NOW.plusSeconds(60));

        assertCode(() -> tx.executeWithoutResult(status -> {
            ApprovalRequest locked = store.lockById(inserted.approvalId()).orElseThrow();
            locked.approve(secondChecker, null, NOW.plusSeconds(120));
            store.update(locked);
        }), ErrorCode.APPROVAL_ALREADY_DECIDED);
    }

    @Test
    void rejectPersistsDecisionWithoutAppliedTimestamp() {
        ApprovalRequest inserted = tx.execute(status -> store.insert(limitRequest()));

        tx.executeWithoutResult(status -> {
            ApprovalRequest locked = store.lockById(inserted.approvalId()).orElseThrow();
            locked.reject(checker, "out of policy", NOW.plusSeconds(60));
            store.update(locked);
        });

        ApprovalRequest rejected = store.findById(inserted.approvalId()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.appliedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT applied_at FROM banking.approval_request WHERE approval_id=?",
                Instant.class, inserted.approvalId())).isNull();
    }

    @Test
    void concurrentCheckersSerializeOnRowLockAndOnlyOneApplies() throws Exception {
        ApprovalRequest inserted = tx.execute(status -> store.insert(limitRequest()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<ErrorCode> first = executor.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        ApprovalRequest locked = store.lockById(inserted.approvalId()).orElseThrow();
                        locked.approve(checker, "first", NOW.plusSeconds(60));
                        store.update(locked);
                        firstLocked.countDown();
                        await(releaseFirst);
                    });
                    return null;
                } catch (BankingBusinessException exception) {
                    return exception.code();
                }
            });
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> second = executor.submit(() -> {
                secondStarted.countDown();
                try {
                    tx.executeWithoutResult(status -> {
                        ApprovalRequest locked = store.lockById(inserted.approvalId()).orElseThrow();
                        locked.approve(secondChecker, "second", NOW.plusSeconds(61));
                        store.update(locked);
                    });
                    return null;
                } catch (BankingBusinessException exception) {
                    return exception.code();
                }
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED);
            ApprovalRequest applied = store.findById(inserted.approvalId()).orElseThrow();
            assertThat(applied.status()).isEqualTo(ApprovalStatus.APPLIED);
            assertThat(applied.checkerId()).isEqualTo(checker);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseEnforcesCanonicalChecksAndForeignKeys() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     correlation_id, decided_at, applied_at)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'APPLIED', ?, ?, 'corr', now(), now())
                """, maker, maker))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_approval_maker_checker");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     correlation_id, decided_at, applied_at)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'APPLIED', ?, NULL, 'corr', now(), now())
                """, maker))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_approval_decided_fields");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     correlation_id, decided_at, applied_at)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'APPLIED', ?, ?, 'corr', now(), NULL)
                """, maker, checker))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_approval_applied_at");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('UNKNOWN', 'CUSTOMER', '2001', '{}'::jsonb, 'PENDING', ?, 'corr')
                """, maker))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_approval_request_type");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'PENDING', 9223372036854775807, 'corr')
                """))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_approval_maker");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'PENDING', ?, repeat('x', 65))
                """, maker))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("character varying(64)");
    }

    @Test
    void storedTargetColumnsMustMatchTypedPayload() {
        long approvalId = tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{
                    "customerId": "2001",
                    "accountId": "3001",
                    "perTransactionLimit": "5000000.00",
                    "dailyLimit": "20000000.00",
                    "currency": "VND",
                    "effectiveFrom": null,
                    "reason": null
                }'::jsonb, 'PENDING', ?, 'corr-mismatch') RETURNING approval_id
                """, Long.class, maker));

        assertThatThrownBy(() -> store.findById(approvalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target");
    }

    @Test
    void mutationRequiresCallerTransaction() {
        assertThatThrownBy(() -> store.insert(limitRequest()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.lockById(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private ApprovalRequest limitRequest() {
        return ApprovalRequest.propose(new LimitChangePayload(2001L, null, new BigDecimal("5000000.00"),
                new BigDecimal("20000000.00"), "VND", null, "customer request"), maker, "corr-1", NOW);
    }

    private long user(String username) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type, status)
                VALUES (?, 'not-a-real-hash', 'STAFF', 'ACTIVE') RETURNING user_id
                """, Long.class, username);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test barrier", exception);
        }
    }
}
