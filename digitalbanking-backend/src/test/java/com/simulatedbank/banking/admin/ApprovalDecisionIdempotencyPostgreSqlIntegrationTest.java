package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.admin.application.AdminIdempotencyService;
import com.simulatedbank.banking.admin.application.AdminRequestHasher;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalStepUpService;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.VerifyCommand;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-ADM-CON-03: decision replay/conflict/in-progress, actor/resource scope, concurrency and rollback. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApprovalDecisionIdempotencyPostgreSqlIntegrationTest {

    private static final String CORRELATION = "adm-con03-decision";
    private static final String REQUEST_ID = "6a1c2e3f-4b5d-4e6f-8a9b-0c1d2e3f4a5b";
    private static final String HASH = "a".repeat(64);
    private static final Set<String> CHECKER_AUTHORITIES =
            Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_decision_idem");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
    }

    @TestConfiguration
    static class OtpCaptureConfig {
        @Bean
        @Primary
        OtpCodeGenerator capturingOtpGenerator(Deque<String> issued) {
            AtomicInteger sequence = new AtomicInteger();
            return () -> {
                String otp = Integer.toString(400000 + sequence.incrementAndGet());
                issued.add(otp);
                return otp.toCharArray();
            };
        }

        @Bean
        Deque<String> issuedOtps() {
            return new ArrayDeque<>();
        }

        @Bean
        OtpGateway acceptingGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) ->
                    new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
        }
    }

    @Autowired private ApprovalDecisionService decisions;
    @Autowired private ApprovalStepUpService stepUp;
    @Autowired private ApprovalRequestStore approvals;
    @Autowired private AdminIdempotencyService idempotency;
    @Autowired private AdminRequestHasher hasher;
    @Autowired private StepUpChallengePort challenges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Deque<String> issuedOtps;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long otherChecker;
    private long target;
    private long otherTarget;
    private final ListAppender<ILoggingEvent> auditLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.user_role WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con03d-%')");
            jdbc.update("DELETE FROM banking.user_privilege WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con03d-%')");
            jdbc.update("DELETE FROM banking.app_user WHERE username LIKE 'adm-con03d-%'");
            maker = staff("adm-con03d-maker-" + suffix, "ADMIN", false);
            checker = staff("adm-con03d-checker-" + suffix, "ADMIN", true);
            otherChecker = staff("adm-con03d-checker2-" + suffix, "ADMIN", true);
            target = staff("adm-con03d-target-" + suffix, "OPERATIONS", false);
            otherTarget = staff("adm-con03d-target2-" + suffix, "OPERATIONS", false);
        });
        issuedOtps.clear();
        Logger logger = (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.admin.AUDIT");
        auditLog.list.clear();
        auditLog.start();
        logger.addAppender(auditLog);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.admin.AUDIT");
        logger.detachAppender(auditLog);
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void approveThenReplayConflictAndOneBusinessEffect() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-approve";
        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");

        DecisionResult first = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", challenge, key);

        assertThat(first.responseStatus()).isEqualTo(200);
        assertThat(first.replayed()).isFalse();
        assertThat(first.approval().status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(first.responseBody()).containsEntry("status", "APPLIED");
        assertThat((Map<String, Object>) first.responseBody().get("payload"))
                .doesNotContainKey("expectedVersion");
        assertThat((Map<String, Object>) first.responseBody().get("result"))
                .containsEntry("previousVersion", 0)
                .containsEntry("resultingVersion", 1);
        assertThat(version(target)).isEqualTo(1L);
        assertThat(roleCodes(target)).contains("AUDITOR");
        assertThat(recordCount()).isEqualTo(1);

        DecisionResult replay = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", null, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseBody()).isEqualTo(first.responseBody());
        assertThat(version(target)).isEqualTo(1L);
        assertThat(auditCount("ROLE_CHANGE_APPROVED")).isEqualTo(1);

        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.REJECT, "denied", null, key), ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void rejectReplayAndConflictWithoutProviderMutation() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-reject";
        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.REJECT, "denied");

        DecisionResult first = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.REJECT, "denied", challenge, key);
        assertThat(first.approval().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(version(target)).isZero();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");

        DecisionResult replay = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.REJECT, "denied", null, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseBody()).isEqualTo(first.responseBody());
        assertThat(auditCount("ROLE_CHANGE_REJECTED")).isEqualTo(1);

        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.REJECT, "other note", null, key), ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void anotherCheckerCannotReplayTheStoredResponse() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-actor";
        String otherChallenge = verifiedChallenge(otherChecker, approvalId, ApprovalDecision.APPROVE, "approved");

        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE, ApprovalDecision.APPROVE, "approved",
                challenge, key);

        assertCode(() -> decideAs(otherChecker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", otherChallenge, key), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(version(target)).isEqualTo(1L);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(auditCount("ROLE_CHANGE_APPROVED")).isEqualTo(1);
    }

    @Test
    void sameKeyIsScopedByApprovalResource() {
        long firstApproval = roleApproval(target, 0L);
        long secondApproval = roleApproval(otherTarget, 0L);
        String key = "key-resource";

        String firstChallenge = verifiedChallenge(checker, firstApproval, ApprovalDecision.APPROVE, "approved");
        String secondChallenge = verifiedChallenge(checker, secondApproval, ApprovalDecision.APPROVE, "approved");

        DecisionResult first = decideAs(checker, firstApproval, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", firstChallenge, key);
        DecisionResult second = decideAs(checker, secondApproval, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", secondChallenge, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(first.approval().status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(second.approval().status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(version(target)).isEqualTo(1L);
        assertThat(version(otherTarget)).isEqualTo(1L);
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyHasOneBusinessEffect() throws Exception {
        long approvalId = roleApproval(target, 0L);
        String key = "key-concurrent";
        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<DecisionResult> call = () -> {
                authenticate(checker, CHECKER_AUTHORITIES);
                barrier.await(10, TimeUnit.SECONDS);
                return decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                        ApprovalDecision.APPROVE, "approved", challenge, CORRELATION, REQUEST_ID, key));
            };
            Future<DecisionResult> first = executor.submit(call);
            Future<DecisionResult> second = executor.submit(call);
            DecisionResult a = first.get(20, TimeUnit.SECONDS);
            DecisionResult b = second.get(20, TimeUnit.SECONDS);

            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(a.approval().approvalId()).isEqualTo(b.approval().approvalId());
            assertThat(version(target)).isEqualTo(1L);
            assertThat(recordCount()).isEqualTo(1);
            assertThat(auditCount("ROLE_CHANGE_APPROVED")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentKeysOnlyOneApplies() throws Exception {
        long approvalId = roleApproval(target, 0L);
        String firstChallenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        String secondChallenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> first = executor.submit(
                    () -> attempt(approvalId, firstChallenge, "key-a", barrier));
            Future<ErrorCode> second = executor.submit(
                    () -> attempt(approvalId, secondChallenge, "key-b", barrier));
            List<ErrorCode> outcomes = java.util.Arrays.asList(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allMatch(code -> code == ErrorCode.APPROVAL_ALREADY_DECIDED
                            || code == ErrorCode.INVALID_STATE_TRANSITION
                            || code == ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            assertThat(version(target)).isEqualTo(1L);
            assertThat(recordCount()).isEqualTo(1);
            assertThat(auditCount("ROLE_CHANGE_APPROVED")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerFailureRollsBackClaimAndTheSameKeyRetries() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-retry";
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.app_user SET role_assignment_version = 5 WHERE user_id = ?", target));
        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", challenge, key), ErrorCode.ROLE_CONFLICT);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(recordCount()).isZero();
        assertThat(challengeStatus(challenge)).isEqualTo("VERIFIED");
        assertThat(version(target)).isEqualTo(5L);

        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.app_user SET role_assignment_version = 0 WHERE user_id = ?", target));
        DecisionResult retried = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", challenge, key);
        assertThat(retried.approval().status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(version(target)).isEqualTo(1L);
    }

    @Test
    void limitApproveFailsClosedAndRejectIsIdempotent() {
        long approvalId = limitApproval();
        String approveKey = "key-limit-approve";
        String approveChallenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.LIMIT_CHANGE,
                ApprovalDecision.APPROVE, "approved", approveChallenge, approveKey),
                ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(recordCount()).isZero();
        assertThat(challengeStatus(approveChallenge)).isEqualTo("VERIFIED");

        String rejectKey = "key-limit-reject";
        String rejectChallenge = verifiedChallenge(checker, approvalId, ApprovalDecision.REJECT, "denied");
        DecisionResult rejected = decideAs(checker, approvalId, ApprovalRequestType.LIMIT_CHANGE,
                ApprovalDecision.REJECT, "denied", rejectChallenge, rejectKey);
        assertThat(rejected.approval().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.responseBody()).doesNotContainKey("result");

        DecisionResult replay = decideAs(checker, approvalId, ApprovalRequestType.LIMIT_CHANGE,
                ApprovalDecision.REJECT, "denied", null, rejectKey);
        assertThat(replay.replayed()).isTrue();
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void durableInProgressRecordReturns409WithoutExecuting() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-in-progress";
        String hash = hasher.decisionHash(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, status, approval_id,
                     created_at, expires_at)
                VALUES (?, ?, 'DECIDE_APPROVAL', ?, ?, 'RECEIVED', ?, now(), now() + interval '1 hour')
                """, key, checker, Long.toString(approvalId), hash, approvalId));

        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", null, key), ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(version(target)).isZero();
    }

    @Test
    void expiredRecordStillReplaysButPurgeDoesNotReopenATerminalApproval() {
        long approvalId = roleApproval(target, 0L);
        String key = "key-expired";
        String challenge = verifiedChallenge(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        DecisionResult first = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", challenge, key);

        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.admin_idempotency_record "
                        + "SET created_at = now() - interval '48 hours', "
                        + "    completed_at = now() - interval '24 hours', "
                        + "    expires_at = now() - interval '24 hours' WHERE idempotency_key = ?", key));

        DecisionResult replay = decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", null, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseBody()).isEqualTo(first.responseBody());

        assertThat(idempotency.purgeExpired(100)).isEqualTo(1);
        assertCode(() -> decideAs(checker, approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", null, key), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(version(target)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    private DecisionResult decideAs(long actor, long approvalId, ApprovalRequestType type,
            ApprovalDecision decision, String note, String challenge, String key) {
        authenticate(actor, CHECKER_AUTHORITIES);
        return decisions.decide(new DecisionCommand(approvalId, type, decision, note, challenge, CORRELATION,
                REQUEST_ID, key));
    }

    private ErrorCode attempt(long approvalId, String challenge, String key, CyclicBarrier barrier)
            throws Exception {
        authenticate(checker, CHECKER_AUTHORITIES);
        barrier.await(10, TimeUnit.SECONDS);
        try {
            decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                    ApprovalDecision.APPROVE, "approved", challenge, CORRELATION, REQUEST_ID, key));
            return null;
        } catch (BankingBusinessException rejection) {
            return rejection.code();
        }
    }

    private long roleApproval(long targetUserId, long expectedVersion) {
        Long id = tx.execute(status -> approvals.insert(ApprovalRequest.propose(
                new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, targetUserId, "AUDITOR", null,
                        expectedVersion, "con03"), maker, "adm-con03-decision", Instant.now())).approvalId());
        assertThat(id).isNotNull();
        return id;
    }

    private long limitApproval() {
        Long id = tx.execute(status -> approvals.insert(ApprovalRequest.propose(
                new LimitChangePayload(2001L, null, new BigDecimal("5000000.00"),
                        new BigDecimal("20000000.00"), "VND", null, "limit"), maker,
                "adm-con03-decision", Instant.now())).approvalId());
        assertThat(id).isNotNull();
        return id;
    }

    private String verifiedChallenge(long actor, long approvalId, ApprovalDecision decision, String note) {
        authenticate(actor, CHECKER_AUTHORITIES);
        ChallengeView view = stepUp.requestDecisionChallenge(approvalId, decision, note, CORRELATION);
        String otp = issuedOtps.pollLast();
        assertThat(otp).as("OTP issued by the capturing generator").isNotNull();
        challenges.verifyChallenge(new VerifyCommand(view.challengeRef(), actor, ChallengePurpose.SENSITIVE_ACTION,
                otp.toCharArray(), CORRELATION));
        return view.challengeRef();
    }

    private void authenticate(long userId, Set<String> authorities) {
        var session = new SessionAuthenticator.Session(Long.toString(userId), null, authorities, HASH);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private long staff(String username, String role, boolean checkerPrivilege) {
        return tx.execute(status -> {
            long id = jdbc.queryForObject("""
                    INSERT INTO banking.app_user (username, password_hash, user_type, status)
                    VALUES (?, 'external-hash', 'STAFF', 'ACTIVE') RETURNING user_id
                    """, Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, role);
            if (checkerPrivilege) {
                jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", id);
            }
            return id;
        });
    }

    private int recordCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.admin_idempotency_record",
                Integer.class);
        return count == null ? 0 : count;
    }

    private String status(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private String challengeStatus(String challengeRef) {
        return jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                String.class, challengeRef);
    }

    private long version(long userId) {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?",
                Long.class, userId);
    }

    private List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private long auditCount(String action) {
        return auditLog.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("action=" + action + " "))
                .count();
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
