package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalStepUpService;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.application.stepup.ApprovalStepUpBinding;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumeCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.VerifyCommand;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
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

/**
 * BE-ADM-CON-02 evidence: real PostgreSQL 16 + Flyway + JPA + Identity step-up port + Identity role command.
 *
 * <p>Only the OTP plaintext seam is a test double (a capturing generator, as in the Identity suite); the
 * challenge state machine, conditional consume, row locking, request hashing, Identity apply and audit adapter
 * are the production implementations.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApprovalStepUpPostgreSqlIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final String CORRELATION = "adm-con02-corr";
    private static final String REQUEST_ID = "3f1c0d8e-9a2b-4c7d-8e5f-6a7b8c9d0e1f";
    private static final String IDEMPOTENCY_KEY = "adm-con02-key";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long UNKNOWN_APPROVAL = 9_000_000_001L;

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_stepup");

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
                String otp = Integer.toString(200000 + sequence.incrementAndGet());
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

    @Autowired private ApprovalRequestStore store;
    @Autowired private ApprovalStepUpService stepUp;
    @Autowired private ApprovalDecisionService decisions;
    @Autowired private ApprovalStepUpBinding binding;
    @Autowired private StepUpChallengePort challenges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Deque<String> issuedOtps;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long otherChecker;
    private long nonChecker;
    private long target;
    private final ListAppender<ILoggingEvent> auditLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.user_role WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con02-%')");
            jdbc.update("DELETE FROM banking.user_privilege WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con02-%')");
        });
        maker = staff("adm-con02-maker-" + suffix, "ADMIN", false);
        checker = staff("adm-con02-checker-" + suffix, "ADMIN", true);
        otherChecker = staff("adm-con02-checker2-" + suffix, "ADMIN", true);
        nonChecker = staff("adm-con02-nochecker-" + suffix, "ADMIN", false);
        target = staff("adm-con02-target-" + suffix, "OPERATIONS", false);
        issuedOtps.clear();
        Logger logger = (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.admin.AUDIT");
        auditLog.list.clear();
        auditLog.start();
        logger.addAppender(auditLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("com.simulatedbank.banking.admin.AUDIT")).detachAppender(auditLog);
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ 1. happy path

    @Test
    void validStepUpApprovesOnceAndAppliesTheRole() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");

        ApprovalRequest decided = decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved",
                challengeRef);

        assertThat(decided.status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(decided.checkerId()).isEqualTo(checker);
        assertThat(decided.appliedAt()).isNotNull();
        assertThat(status(approvalId)).isEqualTo("APPLIED");
        assertThat(consumed(challengeRef)).isTrue();
        assertThat(roleCodes(target)).contains("OPERATIONS").contains("AUDITOR");
        assertThat(version(target)).isEqualTo(1L);
        assertThat(auditActions()).contains("ROLE_CHANGE_APPROVED");
        assertThat(auditActions()).doesNotContain("SECURITY_ACCESS_DENIED");
    }

    // ------------------------------------------------------------------ 2–4. identifiers and missing challenge

    @Test
    void missingApprovalIdIsRejectedAndNeverCreatesAChallenge() {
        assertCode(() -> stepUp.requestDecisionChallenge(0L, ApprovalDecision.APPROVE, null, CORRELATION),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> stepUp.stepUpRequired(0L, ApprovalDecision.APPROVE, null, CORRELATION),
                ErrorCode.VALIDATION_ERROR);
        assertThat(challengeCount()).isZero();
    }

    @Test
    void unknownApprovalIsRejectedWithoutBindingAnything() {
        authenticate(checker);
        assertCode(() -> stepUp.requestDecisionChallenge(UNKNOWN_APPROVAL, ApprovalDecision.APPROVE, null,
                CORRELATION), ErrorCode.APPROVAL_NOT_FOUND);
        assertThat(challengeCount()).isZero();
    }

    @Test
    void missingChallengeIssuesBound428AndDoesNotTransition() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));

        BankingBusinessException required = expectCode(() -> decideAs(checker, approvalId,
                ApprovalDecision.APPROVE, "approved", null), ErrorCode.STEP_UP_REQUIRED);

        @SuppressWarnings("unchecked")
        Map<String, Object> challenge = (Map<String, Object>) required.extensions().get("challenge");
        assertThat(challenge).containsEntry("purpose", "SENSITIVE_ACTION");
        assertThat(challenge.get("challengeId").toString()).matches("[0-9a-f-]{36}");
        assertThat(challenge.get("methods")).isEqualTo(List.of("OTP"));
        assertThat(challenge.get("expiresAt")).isInstanceOf(Instant.class);

        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("SELECT purpose, idempotency_key, request_hash, user_id "
                + "FROM banking.auth_challenge")).satisfies(row -> {
                    assertThat(row.get("purpose")).isEqualTo("SENSITIVE_ACTION");
                    assertThat(row.get("idempotency_key")).isEqualTo(Long.toString(approvalId));
                    assertThat(row.get("request_hash").toString()).matches("[0-9a-f]{64}");
                    assertThat(row.get("user_id")).isEqualTo(checker);
                });
    }

    // ------------------------------------------------------------------ 5–10. binding mismatches

    @Test
    void wrongChallengePurposeIsRejected() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String hash = binding.decisionRequestHash(load(approvalId), checker, ApprovalDecision.APPROVE,
                "approved");
        ChallengeView foreign = challenges.requestChallenge(new ChallengeRequest(ChallengePurpose.TRANSFER_STEP_UP,
                checker, hash, Long.toString(approvalId), CORRELATION));
        verifyChallenge(foreign.challengeRef(), checker, ChallengePurpose.TRANSFER_STEP_UP);

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved",
                foreign.challengeRef()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(foreign.challengeRef())).isFalse();
    }

    @Test
    void anotherCheckerCannotUseTheChallenge() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(otherChecker, approvalId, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
    }

    @Test
    void makerCanNeitherIssueNorUseAStepUpForOwnRequest() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        authenticate(maker);

        assertCode(() -> stepUp.requestDecisionChallenge(approvalId, ApprovalDecision.APPROVE, null, CORRELATION),
                ErrorCode.ROLE_CONFLICT);
        assertCode(() -> decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, null, UUID.randomUUID().toString(), CORRELATION, REQUEST_ID,
                IDEMPOTENCY_KEY + "-maker")), ErrorCode.ROLE_CONFLICT);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(auditActions()).contains("SECURITY_ACCESS_DENIED");
    }

    @Test
    void challengeBoundToAnotherApprovalIsRejected() {
        long first = roleApproval(maker, assignRole("AUDITOR"));
        long second = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(first, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(checker, second, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(status(first)).isEqualTo("PENDING");
        assertThat(status(second)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
    }

    @Test
    void aDifferentDecisionOnTheSameApprovalIsRejected() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.REJECT, "approved", challengeRef),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
    }

    @Test
    void forgedBindingKeyIsRejectedEvenWithTheCorrectHash() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String hash = binding.decisionRequestHash(load(approvalId), checker, ApprovalDecision.APPROVE,
                "approved");
        ChallengeView forged = challenges.requestChallenge(new ChallengeRequest(ChallengePurpose.SENSITIVE_ACTION,
                checker, hash, "999", CORRELATION));
        verifyChallenge(forged.challengeRef(), checker, ChallengePurpose.SENSITIVE_ACTION);

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved",
                forged.challengeRef()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(status(approvalId)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ 11–13. expiry, consumption, replay

    @Test
    void expiredChallengeIsRejected() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");
        expire(challengeRef);

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.AUTH_CHALLENGE_EXPIRED);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
    }

    @Test
    void previouslyConsumedChallengeIsRejected() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.auth_challenge SET consumed_at = now() WHERE challenge_ref = ?::uuid",
                challengeRef));

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(status(approvalId)).isEqualTo("PENDING");
    }

    @Test
    void successfulChallengeCannotBeReplayedForASecondDecision() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.REJECT, "denied");
        decideAs(checker, approvalId, ApprovalDecision.REJECT, "denied", challengeRef);
        Instant firstConsume = consumedAt(challengeRef);

        assertCode(() -> decideAs(otherChecker, approvalId, ApprovalDecision.REJECT, "denied", challengeRef),
                ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(status(approvalId)).isEqualTo("REJECTED");
        assertThat(consumedAt(challengeRef)).isEqualTo(firstConsume);
        assertThat(auditActions()).contains("ROLE_CHANGE_REJECTED").contains("SECURITY_ACCESS_DENIED");
    }

    // ------------------------------------------------------------------ 14–16. payload change, concurrency, provider failure

    @Test
    void changedApprovalPayloadRejectsTheChallenge() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.approval_request SET payload = jsonb_set(payload, '{expectedVersion}', '4') "
                        + "WHERE approval_id = ?", approvalId));

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.REJECT, "denied");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<ErrorCode> first = () -> attempt(checker, approvalId, challengeRef, barrier,
                    IDEMPOTENCY_KEY + "-a");
            Callable<ErrorCode> second = () -> attempt(checker, approvalId, challengeRef, barrier,
                    IDEMPOTENCY_KEY + "-b");
            Future<ErrorCode> a = executor.submit(first);
            Future<ErrorCode> b = executor.submit(second);

            List<ErrorCode> outcomes = new java.util.ArrayList<>(java.util.Arrays.asList(
                    a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allMatch(code -> code == ErrorCode.APPROVAL_ALREADY_DECIDED
                            || code == ErrorCode.INVALID_STATE_TRANSITION
                            || code == ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            assertThat(status(approvalId)).isEqualTo("REJECTED");
            assertThat(consumed(challengeRef)).isTrue();
            assertThat(auditActions()).filteredOn("ROLE_CHANGE_REJECTED"::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerFailureRollsBackConsumptionAndKeepsTheApprovalPending() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.app_user SET role_assignment_version = 5 WHERE user_id = ?", target));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.APPROVE, "approved");

        assertCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved", challengeRef),
                ErrorCode.ROLE_CONFLICT);
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(consumed(challengeRef)).isFalse();
        assertThat(challengeStatus(challengeRef)).isEqualTo("VERIFIED");
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
        assertThat(version(target)).isEqualTo(5L);
    }

    @Test
    void rejectConsumesStepUpWithoutTouchingTheProvider() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String challengeRef = verifiedChallenge(approvalId, ApprovalDecision.REJECT, "denied");

        ApprovalRequest decided = decideAs(checker, approvalId, ApprovalDecision.REJECT, "denied", challengeRef);

        assertThat(decided.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(decided.appliedAt()).isNull();
        assertThat(consumed(challengeRef)).isTrue();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
        assertThat(version(target)).isZero();
        assertThat(auditActions()).contains("ROLE_CHANGE_REJECTED");
    }

    // ------------------------------------------------------------------ 17–21. APP-02 decision gates

    @Test
    void missingChallengeIsNotAuditedAsAnAccessDenial() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));

        expectCode(() -> decideAs(checker, approvalId, ApprovalDecision.APPROVE, "approved", null),
                ErrorCode.STEP_UP_REQUIRED);

        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(challengeCount()).isEqualTo(1);
        assertThat(auditActions()).doesNotContain("SECURITY_ACCESS_DENIED");
    }

    @Test
    void makerSelfRejectionWithoutChallengeIsAudited() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        authenticate(maker);

        assertCode(() -> decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.REJECT, "denied", null, CORRELATION, REQUEST_ID,
                IDEMPOTENCY_KEY + "-maker-reject")), ErrorCode.ROLE_CONFLICT);

        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(challengeCount()).isZero();
        assertThat(auditActions()).contains("SECURITY_ACCESS_DENIED");
    }

    @Test
    void checkerWithoutPrivilegeIsDeniedBeforeAnyLockOrChallenge() {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        authenticate(nonChecker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));

        assertCode(() -> decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, "approved", UUID.randomUUID().toString(), CORRELATION, REQUEST_ID,
                IDEMPOTENCY_KEY + "-no-checker")), ErrorCode.AUTH_FORBIDDEN);

        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(challengeCount()).isZero();
        assertThat(recordCount()).isZero();
        // The HTTP security chain audits the 403; the service entry point fails closed before any state.
    }

    @Test
    void twoCheckersRacingApproveApplyTheRoleOnce() throws Exception {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String firstChallenge = verifiedChallengeFor(checker, approvalId, ApprovalDecision.APPROVE, "approved");
        String secondChallenge = verifiedChallengeFor(otherChecker, approvalId, ApprovalDecision.APPROVE,
                "approved");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ErrorCode> first = executor.submit(() -> attemptDecision(checker, approvalId,
                    ApprovalDecision.APPROVE, "approved", firstChallenge, IDEMPOTENCY_KEY + "-checker-a",
                    barrier));
            Future<ErrorCode> second = executor.submit(() -> attemptDecision(otherChecker, approvalId,
                    ApprovalDecision.APPROVE, "approved", secondChallenge, IDEMPOTENCY_KEY + "-checker-b",
                    barrier));
            List<ErrorCode> outcomes = java.util.Arrays.asList(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allMatch(code -> code == ErrorCode.APPROVAL_ALREADY_DECIDED
                            || code == ErrorCode.INVALID_STATE_TRANSITION
                            || code == ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            assertThat(status(approvalId)).isEqualTo("APPLIED");
            assertThat(roleCodes(target)).contains("AUDITOR");
            assertThat(version(target)).isEqualTo(1L);
            assertThat(recordCount()).isEqualTo(1);
            assertThat(auditActions()).filteredOn("ROLE_CHANGE_APPROVED"::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentApproveAndRejectLeaveOneTerminalDecision() throws Exception {
        long approvalId = roleApproval(maker, assignRole("AUDITOR"));
        String approveChallenge = verifiedChallengeFor(checker, approvalId, ApprovalDecision.APPROVE,
                "approved");
        String rejectChallenge = verifiedChallengeFor(otherChecker, approvalId, ApprovalDecision.REJECT,
                "denied");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ErrorCode> approve = executor.submit(() -> attemptDecision(checker, approvalId,
                    ApprovalDecision.APPROVE, "approved", approveChallenge, IDEMPOTENCY_KEY + "-race-approve",
                    barrier));
            Future<ErrorCode> reject = executor.submit(() -> attemptDecision(otherChecker, approvalId,
                    ApprovalDecision.REJECT, "denied", rejectChallenge, IDEMPOTENCY_KEY + "-race-reject",
                    barrier));
            List<ErrorCode> outcomes = java.util.Arrays.asList(approve.get(20, TimeUnit.SECONDS),
                    reject.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allMatch(code -> code == ErrorCode.APPROVAL_ALREADY_DECIDED
                            || code == ErrorCode.INVALID_STATE_TRANSITION
                            || code == ErrorCode.STEP_UP_CHALLENGE_MISMATCH);

            String terminal = status(approvalId);
            assertThat(terminal).isIn("APPLIED", "REJECTED");
            if ("APPLIED".equals(terminal)) {
                assertThat(roleCodes(target)).contains("AUDITOR");
                assertThat(version(target)).isEqualTo(1L);
                assertThat(consumed(approveChallenge)).isTrue();
                assertThat(consumed(rejectChallenge)).isFalse();
                assertThat(auditActions()).contains("ROLE_CHANGE_APPROVED");
                assertThat(auditActions()).doesNotContain("ROLE_CHANGE_REJECTED");
            } else {
                assertThat(roleCodes(target)).doesNotContain("AUDITOR");
                assertThat(version(target)).isZero();
                assertThat(consumed(rejectChallenge)).isTrue();
                assertThat(consumed(approveChallenge)).isFalse();
                assertThat(auditActions()).contains("ROLE_CHANGE_REJECTED");
                assertThat(auditActions()).doesNotContain("ROLE_CHANGE_APPROVED");
            }
            assertThat(recordCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private ErrorCode attemptDecision(long actor, long approvalId, ApprovalDecision decision, String note,
            String challengeRef, String idempotencyKey, CyclicBarrier barrier) throws Exception {
        authenticate(actor);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE, decision, note,
                    challengeRef, CORRELATION, REQUEST_ID, idempotencyKey));
            return null;
        } catch (BankingBusinessException rejection) {
            return rejection.code();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ErrorCode attempt(long actor, long approvalId, String challengeRef, CyclicBarrier barrier,
            String idempotencyKey) {
        authenticate(actor);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                    ApprovalDecision.REJECT, "denied", challengeRef, CORRELATION, REQUEST_ID, idempotencyKey));
            return null;
        } catch (BankingBusinessException rejection) {
            return rejection.code();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ApprovalRequest decideAs(long actor, long approvalId, ApprovalDecision decision, String note,
            String challengeRef) {
        authenticate(actor);
        return decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE, decision, note,
                challengeRef, CORRELATION, REQUEST_ID, IDEMPOTENCY_KEY)).approval();
    }

    private String verifiedChallenge(long approvalId, ApprovalDecision decision, String note) {
        return verifiedChallengeFor(checker, approvalId, decision, note);
    }

    private String verifiedChallengeFor(long actor, long approvalId, ApprovalDecision decision, String note) {
        authenticate(actor);
        ChallengeView view = stepUp.requestDecisionChallenge(approvalId, decision, note, CORRELATION);
        verifyChallenge(view.challengeRef(), actor, ChallengePurpose.SENSITIVE_ACTION);
        return view.challengeRef();
    }

    private void verifyChallenge(String challengeRef, long userId, ChallengePurpose purpose) {
        String otp = issuedOtps.pollLast();
        assertThat(otp).as("OTP issued by the capturing generator").isNotNull();
        challenges.verifyChallenge(new VerifyCommand(challengeRef, userId, purpose, otp.toCharArray(),
                CORRELATION));
    }

    private long roleApproval(long requester, RoleChangePayload payload) {
        Long id = tx.execute(status -> store.insert(ApprovalRequest.propose(payload, requester,
                "adm-con02-proposal", NOW)).approvalId());
        assertThat(id).isNotNull();
        return id;
    }

    private RoleChangePayload assignRole(String roleCode) {
        return new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, target, roleCode, null, 0L, "con02");
    }

    private ApprovalRequest load(long approvalId) {
        return store.findById(approvalId).orElseThrow();
    }

    private void authenticate(long userId) {
        authenticate(userId, Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER"));
    }

    private void authenticate(long userId, Set<String> claims) {
        var session = new SessionAuthenticator.Session(Long.toString(userId), null, claims, HASH);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, claims.stream().map(SimpleGrantedAuthority::new).toList()));
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

    private void expire(String challengeRef) {
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.auth_challenge SET created_at = created_at - interval '2 seconds', "
                        + "expires_at = now() - interval '1 second' WHERE challenge_ref = ?::uuid", challengeRef));
    }

    private String status(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private boolean consumed(String challengeRef) {
        Instant consumed = consumedAt(challengeRef);
        return consumed != null;
    }

    private Instant consumedAt(String challengeRef) {
        return jdbc.queryForObject("SELECT consumed_at FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                Instant.class, challengeRef);
    }

    private String challengeStatus(String challengeRef) {
        return jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                String.class, challengeRef);
    }

    private long challengeCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM banking.auth_challenge", Long.class);
        return count == null ? 0 : count;
    }

    private long version(long userId) {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?",
                Long.class, userId);
    }

    private long recordCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM banking.admin_idempotency_record", Long.class);
        return count == null ? 0 : count;
    }

    private List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private List<String> auditActions() {
        return auditLog.list.stream()
                .flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> "action".equals(pair.key))
                .map(pair -> String.valueOf(pair.value))
                .toList();
    }

    private static BankingBusinessException expectCode(Runnable action, ErrorCode code) {
        BankingBusinessException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                action::run, BankingBusinessException.class);
        assertThat(failure).as("expected " + code).isNotNull();
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
