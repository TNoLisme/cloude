package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.admin.application.AdminIdempotencyService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.LimitProposalCommand;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.VerifyCommand;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
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

/** BE-ADM-CON-03: proposal idempotency, Maker step-up and fail-closed paths on real PostgreSQL/Identity. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApprovalProposalPostgreSqlIntegrationTest {

    private static final String CORRELATION = "adm-con03-proposal";
    private static final String REQUEST_ID = "8f2d7c1a-3b4e-4d5f-9a6b-7c8d9e0f1a2b";
    private static final String HASH = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("admin_proposal");

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
                String otp = Integer.toString(300000 + sequence.incrementAndGet());
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
    @Autowired private AdminIdempotencyService idempotency;
    @Autowired private StepUpChallengePort challenges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Deque<String> issuedOtps;

    private TransactionTemplate tx;
    private long maker;
    private long otherMaker;
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
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con03p-%')");
            jdbc.update("DELETE FROM banking.user_privilege WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-con03p-%')");
            maker = staff("adm-con03p-maker-" + suffix, "ADMIN");
            otherMaker = staff("adm-con03p-maker2-" + suffix, "ADMIN");
            target = staff("adm-con03p-target-" + suffix, "OPERATIONS");
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
    void firstRequestWithoutChallengeReturns428AndCreatesNothing() {
        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));

        BankingBusinessException required = expectCode(() -> proposals.proposeRole(
                command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, "key-428", null)), ErrorCode.STEP_UP_REQUIRED);

        assertThat(required.extensions()).containsKey("challenge");
        assertThat(approvalCount()).isZero();
        assertThat(recordCount()).isZero();
    }

    @Test
    void retryWithVerifiedChallengeCreatesOneApprovalAndReplays() {
        String key = "key-create";
        ProposalResult first = create(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);

        assertThat(first.responseStatus()).isEqualTo(201);
        assertThat(first.replayed()).isFalse();
        assertThat(first.approvalId()).isPositive();
        assertThat(first.responseBody()).containsEntry("status", "PENDING")
                .containsEntry("requestType", "ROLE_CHANGE")
                .containsEntry("targetType", "APP_USER");
        assertThat(status(first.approvalId())).isEqualTo("PENDING");
        assertThat(approvalCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(recordStatus(key)).isEqualTo("COMPLETED");
        assertThat(auditActions()).contains("ROLE_CHANGE_REQUESTED");

        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        ProposalResult replay = proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR",
                null, key, null));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.approvalId()).isEqualTo(first.approvalId());
        assertThat(replay.responseBody()).isEqualTo(first.responseBody());
        assertThat(approvalCount()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIsAConflict() {
        String key = "key-conflict";
        create(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);

        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        assertCode(() -> proposals.proposeRole(command(RoleChangeOperation.REVOKE_ROLE, "AUDITOR", null,
                key, null)), ErrorCode.IDEMPOTENCY_CONFLICT);

        assertThat(approvalCount()).isEqualTo(1);
        assertThat(auditActions()).contains("SECURITY_ACCESS_DENIED");
    }

    @Test
    void sameKeyAcrossActorsIsIsolated() {
        String key = "key-actor";
        ProposalResult first = createAs(maker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        ProposalResult second = createAs(otherMaker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);

        assertThat(second.approvalId()).isNotEqualTo(first.approvalId());
        assertThat(second.replayed()).isFalse();
        assertThat(approvalCount()).isEqualTo(2);
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void failedChallengeConsumptionRollsBackClaimAndRetrySucceeds() {
        String key = "key-retry";
        String challengeRef = issueChallenge(maker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        verify(challengeRef, maker);

        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        assertCode(() -> proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, key,
                UUID.randomUUID().toString())), ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        assertThat(approvalCount()).isZero();
        assertThat(recordCount()).isZero();

        String fresh = issueChallenge(maker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        verify(fresh, maker);
        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        ProposalResult result = proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null,
                key, fresh));

        assertThat(result.responseStatus()).isEqualTo(201);
        assertThat(approvalCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void changedPayloadAfterChallengeIsRejectedByTheStepUpBinding() {
        String key = "key-binding";
        String challengeRef = issueChallenge(maker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        verify(challengeRef, maker);

        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        assertCode(() -> proposals.proposeRole(command(RoleChangeOperation.REVOKE_ROLE, "AUDITOR", null, key,
                challengeRef)), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);

        assertThat(approvalCount()).isZero();
        assertThat(recordCount()).isZero();
    }

    @Test
    void limitProposalRequiresStepUpAndRejectsAnUnknownCustomerWithoutState() {
        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE", "LIMIT_MAKER"));
        String key = "key-limit";

        BankingBusinessException required = expectCode(
                () -> proposals.proposeLimit(limitCommand(key, null)), ErrorCode.STEP_UP_REQUIRED);
        String challengeRef = ((Map<?, ?>) required.extensions().get("challenge")).get("challengeId").toString();
        verify(challengeRef, maker);
        assertThat(approvalCount()).isZero();
        assertThat(recordCount()).isZero();
        assertThat(limitCount()).isZero();

        // Real Limit provider: the customer does not exist, so the proposal fails before any state is written.
        assertCode(() -> proposals.proposeLimit(limitCommand(key, challengeRef)), ErrorCode.CUSTOMER_NOT_FOUND);
        assertThat(approvalCount()).isZero();
        assertThat(recordCount()).isZero();
        assertThat(limitCount()).isZero();
    }

    private static LimitProposalCommand limitCommand(String key, String challengeRef) {
        return new LimitProposalCommand(2001L, null, new BigDecimal("5000000.00"),
                new BigDecimal("20000000.00"), "VND", null, "reason", key, challengeRef, CORRELATION, REQUEST_ID);
    }

    private long limitCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneApproval() throws Exception {
        String key = "key-concurrent";
        String challengeRef = issueChallenge(maker, RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        verify(challengeRef, maker);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ProposalResult> call = () -> {
                authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
                barrier.await(10, TimeUnit.SECONDS);
                return proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, key,
                        challengeRef));
            };
            Future<ProposalResult> first = executor.submit(call);
            Future<ProposalResult> second = executor.submit(call);
            ProposalResult a = first.get(20, TimeUnit.SECONDS);
            ProposalResult b = second.get(20, TimeUnit.SECONDS);

            assertThat(a.responseStatus()).isEqualTo(201);
            assertThat(b.responseStatus()).isEqualTo(201);
            assertThat(a.approvalId()).isEqualTo(b.approvalId());
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(approvalCount()).isEqualTo(1);
            assertThat(recordCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRecordStillReplaysUntilPurge() {
        String key = "key-expired";
        ProposalResult first = create(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.admin_idempotency_record "
                        + "SET created_at = now() - interval '48 hours', "
                        + "    completed_at = now() - interval '24 hours', "
                        + "    expires_at = now() - interval '24 hours' "
                        + "WHERE idempotency_key = ?", key));

        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        ProposalResult replay = proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null,
                key, null));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.approvalId()).isEqualTo(first.approvalId());

        assertThat(idempotency.purgeExpired(100)).isEqualTo(1);
        authenticate(maker, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        assertCode(() -> proposals.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null,
                key, null)), ErrorCode.STEP_UP_REQUIRED);

        ProposalResult afterPurge = create(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", key);
        assertThat(afterPurge.approvalId()).isNotEqualTo(first.approvalId());
        assertThat(approvalCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private ProposalResult create(RoleChangeOperation operation, String roleCode, String key) {
        return createAs(maker, operation, roleCode, key);
    }

    private ProposalResult createAs(long actor, RoleChangeOperation operation, String roleCode, String key) {
        String challengeRef = issueChallenge(actor, operation, roleCode, key);
        verify(challengeRef, actor);
        authenticate(actor, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        return proposals.proposeRole(command(operation, roleCode, null, key, challengeRef));
    }

    private String issueChallenge(long actor, RoleChangeOperation operation, String roleCode, String key) {
        authenticate(actor, Set.of("ROLE_ADMIN", "ROLE_MANAGE"));
        BankingBusinessException required = expectCode(() -> proposals.proposeRole(
                command(operation, roleCode, null, key, null)), ErrorCode.STEP_UP_REQUIRED);
        return ((Map<?, ?>) required.extensions().get("challenge")).get("challengeId").toString();
    }

    private void verify(String challengeRef, long actor) {
        String otp = issuedOtps.pollLast();
        assertThat(otp).as("OTP issued by the capturing generator").isNotNull();
        challenges.verifyChallenge(new VerifyCommand(challengeRef, actor, ChallengePurpose.SENSITIVE_ACTION,
                otp.toCharArray(), CORRELATION));
    }

    private RoleProposalCommand command(RoleChangeOperation operation, String roleCode, String privilegeCode,
            String key, String challengeRef) {
        return new RoleProposalCommand(operation, target, roleCode, privilegeCode, "con03", key, challengeRef,
                CORRELATION, REQUEST_ID);
    }

    private void authenticate(long userId, Set<String> authorities) {
        var session = new SessionAuthenticator.Session(Long.toString(userId), null, authorities, HASH);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private long staff(String username, String role) {
        return tx.execute(status -> {
            long id = jdbc.queryForObject("""
                    INSERT INTO banking.app_user (username, password_hash, user_type, status)
                    VALUES (?, 'external-hash', 'STAFF', 'ACTIVE') RETURNING user_id
                    """, Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, role);
            return id;
        });
    }

    private int approvalCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.approval_request", Integer.class);
        return count == null ? 0 : count;
    }

    private int recordCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.admin_idempotency_record",
                Integer.class);
        return count == null ? 0 : count;
    }

    private String recordStatus(String key) {
        return jdbc.queryForObject("SELECT status FROM banking.admin_idempotency_record WHERE idempotency_key = ?",
                String.class, key);
    }

    private String status(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
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
