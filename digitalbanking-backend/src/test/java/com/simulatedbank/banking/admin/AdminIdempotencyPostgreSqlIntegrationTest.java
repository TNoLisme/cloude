package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.AdminIdempotencyService;
import com.simulatedbank.banking.admin.application.AdminIdempotencyService.Claim;
import com.simulatedbank.banking.admin.application.AdminIdempotencyService.Precheck;
import com.simulatedbank.banking.admin.application.port.AdminIdempotencyStore;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyOperation;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-ADM-CON-03: real PostgreSQL evidence for scope, replay/conflict/in-progress and purge. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AdminIdempotencyPostgreSqlIntegrationTest {

    private static final String KEY = "adm-con03-key";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_idempotency");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
    }

    @Autowired private AdminIdempotencyService service;
    @Autowired private AdminIdempotencyStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long actor;
    private long otherActor;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.app_user WHERE username LIKE 'adm-con03-%'");
            actor = user("adm-con03-actor-" + suffix);
            otherActor = user("adm-con03-other-" + suffix);
        });
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.app_user WHERE username LIKE 'adm-con03-%'");
        });
    }

    @Test
    void claimCompleteThenReplayConflictAndInProgress() {
        AdminIdempotencyScope scope = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        Map<String, Object> body = Map.of("approvalId", "1", "status", "PENDING");
        long recordId = tx.execute(status -> {
            Claim claimed = service.claim(scope, KEY, HASH);
            assertThat(claimed).isInstanceOf(Claim.Claimed.class);
            long id = ((Claim.Claimed) claimed).recordId();
            service.complete(id, 201, body, approval(actor));
            return id;
        });
        assertThat(recordId).isPositive();

        assertThat(service.precheck(scope, KEY, HASH))
                .isInstanceOfSatisfying(Precheck.Replay.class,
                        replay -> assertThat(replay.record().requiredResponseBody()).isEqualTo(body));

        assertConflict(() -> service.precheck(scope, KEY, OTHER_HASH));

        // A durable RECEIVED row can only come from a future two-phase flow; it must never execute again.
        AdminIdempotencyScope receivedScope = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_LIMIT_PROPOSAL);
        insertRecord("adm-con03-received", actor, "CREATE_LIMIT_PROPOSAL", "", HASH, "RECEIVED",
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                null, null, null);
        assertInProgress(() -> service.precheck(receivedScope, "adm-con03-received", HASH));
    }

    @Test
    void sameKeyIsIsolatedByActorOperationAndResource() {
        AdminIdempotencyScope actorScope = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        AdminIdempotencyScope otherActorScope = AdminIdempotencyScope.proposal(otherActor,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        AdminIdempotencyScope otherOperation = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_LIMIT_PROPOSAL);

        java.util.Optional<AdminIdempotencyRecord> claimed =
                tx.execute(status -> store.claim(actorScope, KEY, HASH,
                        Instant.now(), Instant.now().plusSeconds(3600)));
        java.util.Optional<AdminIdempotencyRecord> otherActorClaim =
                tx.execute(status -> store.claim(otherActorScope, KEY, HASH,
                        Instant.now(), Instant.now().plusSeconds(3600)));
        java.util.Optional<AdminIdempotencyRecord> otherOperationClaim =
                tx.execute(status -> store.claim(otherOperation, KEY, HASH,
                        Instant.now(), Instant.now().plusSeconds(3600)));
        java.util.Optional<AdminIdempotencyRecord> conflicting =
                tx.execute(status -> store.claim(actorScope, KEY, OTHER_HASH,
                        Instant.now(), Instant.now().plusSeconds(3600)));

        assertThat(claimed).isPresent();
        assertThat(otherActorClaim).isPresent();
        assertThat(otherOperationClaim).isPresent();
        // Same scope/key is the unique arbiter; a different hash still loses the insert.
        assertThat(conflicting).isEmpty();
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        AdminIdempotencyScope scope = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return tx.execute(status -> store.claim(scope, KEY, HASH,
                        Instant.now(), Instant.now().plusSeconds(3600))).isPresent();
            };
            Future<Boolean> first = executor.submit(claim);
            Future<Boolean> second = executor.submit(claim);

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.admin_idempotency_record WHERE actor_id = ?", Integer.class,
                    actor)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completionExtendsExpiryFromCompletionTime() {
        AdminIdempotencyScope scope = AdminIdempotencyScope.proposal(actor,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        tx.executeWithoutResult(status -> {
            Claim claimed = service.claim(scope, KEY, HASH);
            service.complete(((Claim.Claimed) claimed).recordId(), 201, Map.of("ok", "true"),
                    approval(actor));
        });

        AdminIdempotencyRecord record = tx.execute(status -> store.find(scope, KEY).orElseThrow());
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.expiresAt()).isAfterOrEqualTo(record.completedAt().plus(service.ttl())
                .minusSeconds(1));
    }

    @Test
    void purgeDeletesOnlyTerminalExpiredRecords() {
        Instant now = Instant.now();
        Instant oldCreated = now.minus(48, ChronoUnit.HOURS);
        Instant oldExpiry = now.minus(24, ChronoUnit.HOURS);
        Instant futureExpiry = now.plus(24, ChronoUnit.HOURS);
        insertRecord("adm-con03-expired-completed", actor, "CREATE_ROLE_PROPOSAL", "", HASH, "COMPLETED",
                oldCreated, oldExpiry, 201, "{\"ok\":true}", now.minus(24, ChronoUnit.HOURS));
        insertRecord("adm-con03-live-completed", actor, "CREATE_ROLE_PROPOSAL", "1", HASH, "COMPLETED",
                oldCreated, futureExpiry, 201, "{\"ok\":true}", now.minus(24, ChronoUnit.HOURS));
        insertRecord("adm-con03-expired-received", actor, "CREATE_LIMIT_PROPOSAL", "2", HASH, "RECEIVED",
                oldCreated, oldExpiry, null, null, null);

        int deleted = service.purgeExpired(100);

        assertThat(deleted).isEqualTo(1);
        assertThat(keys()).contains("adm-con03-live-completed", "adm-con03-expired-received")
                .doesNotContain("adm-con03-expired-completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.admin_idempotency_record "
                + "WHERE status = 'RECEIVED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseRejectsUnsafeIdempotencyStates() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertRecord("adm-con03-bad-hash", actor, "CREATE_ROLE_PROPOSAL", "",
                "not-a-hash", "RECEIVED", now, now.plusSeconds(3600), null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRecord("adm-con03-terminal-no-body", actor, "CREATE_ROLE_PROPOSAL",
                "", HASH, "COMPLETED", now, now.plusSeconds(3600), 201, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRecord("adm-con03-decision-no-ref", actor, "DECIDE_APPROVAL", "99",
                HASH, "RECEIVED", now, now.plusSeconds(3600), null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long approval(long makerId) {
        return tx.execute(status -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, correlation_id,
                         created_at)
                    VALUES ('ROLE_CHANGE', 'APP_USER', '1005',
                        '{"operation":"GRANT_PRIVILEGE","targetUserId":"1005","roleCode":null,
                          "privilegeCode":"CHECKER","expectedVersion":0,"reason":null}'::jsonb,
                        'PENDING', ?, 'corr-con03', now())
                    RETURNING approval_id
                    """, Long.class, makerId);
            return id == null ? 0L : id;
        });
    }

    private long user(String username) {
        return tx.execute(status -> jdbc.queryForObject(
                "INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                        + "VALUES (?, 'hash', 'STAFF', 'ACTIVE') RETURNING user_id",
                Long.class, username));
    }

    private void insertRecord(String key, long actorId, String operation, String scopeKey, String hash,
            String status, Instant createdAt, Instant expiresAt, Integer responseStatus, String responseBody,
            Instant completedAt) {
        tx.executeWithoutResult(txStatus -> jdbc.update("""
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, status, response_status,
                     response_body, created_at, expires_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, key, actorId, operation, scopeKey, hash, status, responseStatus, responseBody,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(expiresAt),
                completedAt == null ? null : java.sql.Timestamp.from(completedAt)));
    }

    private List<String> keys() {
        return jdbc.queryForList("SELECT idempotency_key FROM banking.admin_idempotency_record", String.class);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BankingBusinessException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    private static void assertInProgress(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BankingBusinessException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
    }
}
