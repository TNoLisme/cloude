package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.application.ApprovalStepUpService;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.application.stepup.ApprovalStepUpBinding;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
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
import org.junit.jupiter.api.Timeout;
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
 * BE-ADM-TST-03: deterministic concurrency/idempotency tests with independent PostgreSQL connections.
 *
 * <p>Every worker uses the real application service, a server-side session, its own Spring transaction and its own
 * pooled connection. Workers synchronize on a {@link CyclicBarrier} and every wait is bounded; no test relies on
 * {@code Thread.sleep}. Failures from either worker are propagated through {@link Future#get(long, TimeUnit)}.</p>
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>same proposal key + different canonical hash → one claim, one {@code IDEMPOTENCY_CONFLICT};</li>
 *   <li>two distinct keys for the same target → both PENDING because canonical {@code 04 §4.5.3} has no partial
 *       unique PENDING index ({@code ADM-OI-03} remains an unapproved extension);</li>
 *   <li>two PENDING approvals for the same target applied concurrently → one {@code APPLIED}, one
 *       {@code ROLE_CONFLICT}, no double revoke/apply;</li>
 *   <li>one verified challenge consumed by two independent transactions → exactly one consumer.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Timeout(60)
class AdminConcurrencyPostgreSqlIntegrationTest {

    private static final String CORRELATION = "adm-tst03-concurrency";
    private static final String REQUEST_ID = "7c9e1a2b-3d4f-4a5b-8c6d-9e0f1a2b3c4d";
    private static final String HASH = "a".repeat(64);
    private static final Set<String> MAKER_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_MANAGE");
    private static final Set<String> CHECKER_AUTHORITIES =
            Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");
    private static final Set<ErrorCode> DECISION_RACE_LOSERS = Set.of(
            ErrorCode.APPROVAL_ALREADY_DECIDED,
            ErrorCode.INVALID_STATE_TRANSITION,
            ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
            ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
    private static final Set<ErrorCode> CONSUME_RACE_LOSERS = Set.of(
            ErrorCode.INVALID_STATE_TRANSITION,
            ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
            ErrorCode.AUTH_CHALLENGE_NOT_FOUND);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_tst03");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        // Two concurrent workers need two connections at the same time; the rest of the pool serves assertions.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
    }

    @TestConfiguration
    static class OtpCaptureConfig {
        @Bean
        @Primary
        OtpCodeGenerator capturingOtpGenerator(Deque<String> issued) {
            AtomicInteger sequence = new AtomicInteger();
            return () -> {
                String otp = Integer.toString(900000 + sequence.incrementAndGet());
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

    @Autowired private ApprovalProposalService proposals;
    @Autowired private ApprovalDecisionService decisions;
    @Autowired private ApprovalStepUpService stepUp;
    @Autowired private ApprovalStepUpBinding binding;
    @Autowired private ApprovalRequestStore approvals;
    @Autowired private StepUpChallengePort challenges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Deque<String> issuedOtps;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long target;
    private final ListAppender<ILoggingEvent> auditLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.user_role WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-tst03-%')");
            jdbc.update("DELETE FROM banking.user_privilege WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-tst03-%')");
            maker = staff("adm-tst03-maker-" + suffix, "ADMIN", false);
            checker = staff("adm-tst03-checker-" + suffix, "ADMIN", true);
            target = staff("adm-tst03-target-" + suffix, "OPERATIONS", false);
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

    // ---------------------------------------------------------------- same key + different hash

    @Test
    void concurrentSameProposalKeyWithDifferentHashHasOneWinnerAndOneConflict() throws Exception {
        String key = "tst03-hash-race";
        String auditorChallenge = verifiedProposalChallenge("AUDITOR", key);
        String operationsChallenge = verifiedProposalChallenge("OPERATIONS", key);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ProposalAttempt> outcomes = runPair(
                () -> propose("AUDITOR", key, auditorChallenge, barrier),
                () -> propose("OPERATIONS", key, operationsChallenge, barrier));

        ProposalAttempt winner = outcomes.stream().filter(attempt -> attempt.error() == null)
                .findFirst().orElseThrow();
        ProposalAttempt loser = outcomes.stream().filter(attempt -> attempt.error() != null)
                .findFirst().orElseThrow();

        assertThat(winner.responseStatus()).isEqualTo(201);
        assertThat(loser.error()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(winner.approvalId()).isPositive();

        // The committed operation is not overwritten by the conflicting request with the same key.
        assertThat(approvalCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(status(winner.approvalId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT payload ->> 'roleCode' FROM banking.approval_request WHERE approval_id = ?",
                String.class, winner.approvalId())).isEqualTo(winner.roleCode());

        // Exactly the winner's proof was consumed; the losing request never consumed or persisted anything.
        String winnerChallenge = "AUDITOR".equals(winner.roleCode()) ? auditorChallenge : operationsChallenge;
        String loserChallenge = "AUDITOR".equals(winner.roleCode()) ? operationsChallenge : auditorChallenge;
        assertThat(consumedAt(winnerChallenge)).isNotNull();
        assertThat(consumedAt(loserChallenge)).isNull();
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isEqualTo(1);
        assertThat(auditFacts("SECURITY_ACCESS_DENIED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- two PENDING targets

    @Test
    void twoConcurrentProposalsForTheSameTargetPersistTwoPendingRowsPerCanonicalSchema() throws Exception {
        String firstKey = "tst03-target-a";
        String secondKey = "tst03-target-b";
        String firstChallenge = verifiedProposalChallenge("AUDITOR", firstKey);
        String secondChallenge = verifiedProposalChallenge("AUDITOR", secondKey);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ProposalAttempt> outcomes = runPair(
                () -> propose("AUDITOR", firstKey, firstChallenge, barrier),
                () -> propose("AUDITOR", secondKey, secondChallenge, barrier));

        assertThat(outcomes).allSatisfy(attempt -> {
            assertThat(attempt.error()).isNull();
            assertThat(attempt.responseStatus()).isEqualTo(201);
        });
        assertThat(outcomes.get(0).approvalId()).isNotEqualTo(outcomes.get(1).approvalId());

        // Canonical 04 §4.5.3 intentionally has no partial unique PENDING index. Two independent maker requests
        // targeting the same APP_USER are both persisted; ADM-OI-03 would change this and is not approved.
        assertThat(pendingForTarget()).isEqualTo(2);
        assertThat(approvalCount()).isEqualTo(2);
        assertThat(recordCount()).isEqualTo(2);
        assertThat(challengesConsumed()).isEqualTo(2);

        // No provider effect at proposal time: the target's role version and assignment are untouched.
        assertThat(roleVersion(target)).isZero();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
    }

    @Test
    void twoPendingApprovalsForTheSameTargetApplyOnlyOnce() throws Exception {
        long firstApproval = pendingRoleApproval();
        long secondApproval = pendingRoleApproval();
        String firstChallenge = verifiedDecisionChallenge(firstApproval);
        String secondChallenge = verifiedDecisionChallenge(secondApproval);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ErrorCode> outcomes = runPair(
                () -> decideAsChecker(firstApproval, firstChallenge, "tst03-apply-a", barrier),
                () -> decideAsChecker(secondApproval, secondChallenge, "tst03-apply-b", barrier));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(java.util.Objects::nonNull).containsExactly(ErrorCode.ROLE_CONFLICT);

        long applied = outcomes.get(0) == null ? firstApproval : secondApproval;
        long conflicting = outcomes.get(0) == null ? secondApproval : firstApproval;

        assertThat(status(applied)).isEqualTo("APPLIED");
        assertThat(status(conflicting)).isEqualTo("PENDING");
        assertThat(checkerId(conflicting)).isNull();
        assertThat(decidedAt(conflicting)).isNull();

        // Only one provider change and one C2 fact survive; the loser rolled back its claim and challenge proof.
        assertThat(roleVersion(target)).isEqualTo(1L);
        assertThat(roleCodes(target).stream().filter("AUDITOR"::equals).count()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(challengesConsumed()).isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(auditFacts("SECURITY_ACCESS_DENIED")).isEqualTo(1);
        String winnerChallenge = applied == firstApproval ? firstChallenge : secondChallenge;
        assertThat(consumedAt(winnerChallenge)).isNotNull();
    }

    // ---------------------------------------------------------------- one challenge, two consumers

    @Test
    void oneVerifiedChallengeCanBeConsumedByOnlyOneIndependentTransaction() throws Exception {
        long approvalId = pendingRoleApproval();
        ApprovalRequest approval = approvals.findById(approvalId).orElseThrow();
        String challengeRef = verifiedDecisionChallenge(approvalId);
        String requestHash = binding.decisionRequestHash(approval, checker, ApprovalDecision.APPROVE,
                "approved");
        String bindingKey = binding.bindingKey(approvalId);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ErrorCode> outcomes = runPair(
                () -> consume(challengeRef, requestHash, bindingKey, barrier),
                () -> consume(challengeRef, requestHash, bindingKey, barrier));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                .allSatisfy(code -> assertThat(CONSUME_RACE_LOSERS).contains(code));
        assertThat(challengesConsumed()).isEqualTo(1);

        // The challenge cannot be used a second time for the protected action: no transition, no claim.
        authenticate(checker, CHECKER_AUTHORITIES);
        assertThatThrownBy(() -> decisions.decide(new DecisionCommand(approvalId,
                ApprovalRequestType.ROLE_CHANGE, ApprovalDecision.APPROVE, "approved", challengeRef,
                CORRELATION, REQUEST_ID, "tst03-consumed")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(DECISION_RACE_LOSERS).contains(failure.code()));
        assertThat(status(approvalId)).isEqualTo("PENDING");
        assertThat(recordCount()).isZero();
        assertThat(roleVersion(target)).isZero();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
    }

    // ---------------------------------------------------------------- workers

    private ProposalAttempt propose(String roleCode, String key, String challengeRef, CyclicBarrier barrier)
            throws Exception {
        authenticate(maker, MAKER_AUTHORITIES);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            ProposalResult result = proposals.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE,
                    target, roleCode, null, "tst03", key, challengeRef, CORRELATION, REQUEST_ID));
            return new ProposalAttempt(result.responseStatus(), result.approvalId(), roleCode, null);
        } catch (BankingBusinessException rejection) {
            return new ProposalAttempt(-1, 0L, roleCode, rejection.code());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ErrorCode decideAsChecker(long approvalId, String challengeRef, String key, CyclicBarrier barrier)
            throws Exception {
        authenticate(checker, CHECKER_AUTHORITIES);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                    ApprovalDecision.APPROVE, "approved", challengeRef, CORRELATION, REQUEST_ID, key));
            return null;
        } catch (BankingBusinessException rejection) {
            return rejection.code();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ErrorCode consume(String challengeRef, String requestHash, String bindingKey, CyclicBarrier barrier)
            throws Exception {
        authenticate(checker, CHECKER_AUTHORITIES);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> challenges.consumeVerified(new ConsumeCommand(challengeRef, checker,
                    ChallengePurpose.SENSITIVE_ACTION, requestHash, bindingKey)));
            return null;
        } catch (BankingBusinessException rejection) {
            return rejection.code();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> List<T> runPair(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = executor.submit(first);
            Future<T> secondResult = executor.submit(second);
            T a = firstResult.get(20, TimeUnit.SECONDS);
            T b = secondResult.get(20, TimeUnit.SECONDS);
            return java.util.Arrays.asList(a, b);
        } finally {
            executor.shutdownNow();
            // A leaked worker would make the next test contend on the same pool/rows.
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------------------------------------------------------------- fixtures and queries

    private long pendingRoleApproval() {
        Long id = tx.execute(status -> approvals.insert(ApprovalRequest.propose(
                new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, target, "AUDITOR", null, 0L, "tst03"),
                maker, CORRELATION, Instant.now())).approvalId());
        assertThat(id).isNotNull();
        return id;
    }

    private String verifiedProposalChallenge(String roleCode, String key) {
        authenticate(maker, MAKER_AUTHORITIES);
        BankingBusinessException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> proposals.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE, target,
                        roleCode, null, "tst03", key, null, CORRELATION, REQUEST_ID)),
                BankingBusinessException.class);
        assertThat(required).isNotNull();
        assertThat(required.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
        @SuppressWarnings("unchecked")
        Map<String, Object> challenge = (Map<String, Object>) required.extensions().get("challenge");
        String challengeRef = challenge.get("challengeId").toString();
        String otp = issuedOtps.pollLast();
        assertThat(otp).isNotNull();
        challenges.verifyChallenge(new VerifyCommand(challengeRef, maker, ChallengePurpose.SENSITIVE_ACTION,
                otp.toCharArray(), CORRELATION));
        return challengeRef;
    }

    private String verifiedDecisionChallenge(long approvalId) {
        authenticate(checker, CHECKER_AUTHORITIES);
        ChallengeView view = stepUp.requestDecisionChallenge(approvalId, ApprovalDecision.APPROVE, "approved",
                CORRELATION);
        String otp = issuedOtps.pollLast();
        assertThat(otp).isNotNull();
        challenges.verifyChallenge(new VerifyCommand(view.challengeRef(), checker,
                ChallengePurpose.SENSITIVE_ACTION, otp.toCharArray(), CORRELATION));
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

    private int approvalCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.approval_request", Integer.class);
        return count == null ? 0 : count;
    }

    private int pendingForTarget() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM banking.approval_request
                 WHERE request_type = 'ROLE_CHANGE' AND target_type = 'APP_USER'
                   AND target_id = ? AND status = 'PENDING'
                """, Integer.class, Long.toString(target));
        return count == null ? 0 : count;
    }

    private int recordCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_idempotency_record", Integer.class);
        return count == null ? 0 : count;
    }

    private String status(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private Long checkerId(long approvalId) {
        return jdbc.queryForObject("SELECT checker_id FROM banking.approval_request WHERE approval_id = ?",
                Long.class, approvalId);
    }

    private Instant decidedAt(long approvalId) {
        return jdbc.queryForObject("SELECT decided_at FROM banking.approval_request WHERE approval_id = ?",
                Instant.class, approvalId);
    }

    private Instant consumedAt(String challengeRef) {
        return jdbc.queryForObject("SELECT consumed_at FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                Instant.class, challengeRef);
    }

    private int challengesConsumed() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_challenge WHERE consumed_at IS NOT NULL", Integer.class);
        return count == null ? 0 : count;
    }

    private long roleVersion(long userId) {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?",
                Long.class, userId);
    }

    private List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private long auditFacts(String action) {
        return auditLog.list.stream()
                .flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> "action".equals(pair.key) && action.equals(pair.value))
                .count();
    }

    private record ProposalAttempt(int responseStatus, long approvalId, String roleCode, ErrorCode error) {
    }
}
