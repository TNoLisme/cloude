package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.admin.application.AdminAuditPort;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.application.ApprovalStepUpService;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-TST-02: PostgreSQL constraint, duplicate-PENDING and transaction-boundary integration tests for Admin.
 *
 * <p>Schema metadata (columns, PK/FK/CHECK/indexes, Flyway history) is asserted against real PostgreSQL by
 * {@code ApprovalRequestV6MigrationPostgreSqlIntegrationTest} and
 * {@code AdminIdempotencyV7MigrationPostgreSqlIntegrationTest}. This class owns the DML-level contract that those
 * tests do not cover: what happens to persisted Admin state when a constraint fails inside a transaction, how a
 * duplicate PENDING request is handled by the approved schema, and whether a C2 audit failure leaves any partial
 * approval/idempotency/challenge state.</p>
 *
 * <p>No H2, no mocked transaction manager and no mocked repository. Constraints are enforced by PostgreSQL and the
 * service paths execute the production {@code TransactionTemplate}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Timeout(60)
class AdminPersistencePostgreSqlIntegrationTest {

    private static final String CORRELATION = "adm-tst02-correlation";
    private static final String REQUEST_ID = "1f0d6c8e-2b3a-4c5d-8e9f-0a1b2c3d4e5f";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final Set<String> MAKER_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_MANAGE");

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_tst02");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    @TestConfiguration
    static class OtpCaptureConfig {
        @Bean
        @Primary
        OtpCodeGenerator capturingOtpGenerator(Deque<String> issued) {
            AtomicInteger sequence = new AtomicInteger();
            return () -> {
                String otp = Integer.toString(800000 + sequence.incrementAndGet());
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
    @Autowired private ApprovalStepUpService stepUp;
    @Autowired private StepUpChallengePort challenges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Deque<String> issuedOtps;
    @MockitoSpyBean private AdminAuditPort audit;

    private TransactionTemplate tx;
    private long maker;
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
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-tst02-%')");
            jdbc.update("DELETE FROM banking.user_privilege WHERE user_id IN "
                    + "(SELECT user_id FROM banking.app_user WHERE username LIKE 'adm-tst02-%')");
            maker = staff("adm-tst02-maker-" + suffix, "ADMIN", false);
            target = staff("adm-tst02-target-" + suffix, "OPERATIONS", false);
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
        reset(audit);
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- duplicate PENDING + unique scope

    @Test
    void canonicalSchemaAllowsTwoPendingTargetsButTheIdempotencyScopeIsUnique() {
        long first = insertPendingApproval(maker, Long.toString(target), "adm-tst02-pending-a");
        long second = insertPendingApproval(maker, Long.toString(target), "adm-tst02-pending-b");

        // 04 §4.5.3 canonical DDL has no partial unique PENDING index (ADM-OI-03 is an unapproved extension),
        // so two independent proposals for the same logical target are both persisted.
        assertThat(first).isNotEqualTo(second);
        assertThat(pendingCountForTarget(target)).isEqualTo(2);
        assertThat(status(first)).isEqualTo("PENDING");
        assertThat(status(second)).isEqualTo("PENDING");

        // The approved uniqueness rule is the Admin idempotency scope, not the target: one claim per
        // (actor, operation, scope, key), regardless of request hash.
        String key = "adm-tst02-scope";
        insertClaim(key, maker, "", HASH);

        assertThatThrownBy(() -> insertClaim(key, maker, "", OTHER_HASH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_admin_idempotency_scope");

        Map<String, Object> original = jdbc.queryForMap("""
                SELECT status, request_hash, response_status, response_body
                  FROM banking.admin_idempotency_record
                 WHERE actor_id = ? AND operation = 'CREATE_ROLE_PROPOSAL' AND scope_key = ''
                   AND idempotency_key = ?
                """, maker, key);
        assertThat(original)
                .containsEntry("status", "RECEIVED")
                .containsEntry("request_hash", HASH)
                .containsEntry("response_status", null)
                .containsEntry("response_body", null);
        assertThat(claimCount(key)).isEqualTo(1);

        // A distinct actor scope is still insertable: uniqueness is exactly the canonical tuple.
        long otherMaker = staff("adm-tst02-other-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "ADMIN", false);
        insertClaim(key, otherMaker, "", HASH);
        assertThat(claimCount(key)).isEqualTo(2);
    }

    @Test
    void failedForeignKeyInsideATransactionRollsBackEveryEarlierWrite() throws Exception {
        String marker = "adm-tst02-rollback-" + UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            long valid = insertPendingApproval(connection, maker, Long.toString(target), marker);

            SQLException failure = insertFailure(connection, () -> insertPendingApproval(connection,
                    Long.MAX_VALUE, Long.toString(target), marker));
            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("23503");
            assertThat(failure.getMessage()).contains("fk_approval_maker");

            connection.rollback();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.approval_request WHERE approval_id = ?", Integer.class, valid))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.approval_request WHERE correlation_id = ?",
                    Integer.class, marker)).isZero();
        }

        // The same insert commits when no constraint failure occurs: rollback was not a connection-wide loss.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertPendingApproval(connection, maker, Long.toString(target), marker);
            connection.commit();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.approval_request WHERE correlation_id = ?",
                Integer.class, marker)).isEqualTo(1);
    }

    @Test
    void foreignKeyRestrictLeavesTheCommittedApprovalIntact() throws Exception {
        long approval = insertPendingApproval(maker, Long.toString(target), "adm-tst02-restrict");
        // Remove the unrelated RBAC reference so the only remaining blocker is the Admin approval FK.
        tx.executeWithoutResult(status ->
                jdbc.update("DELETE FROM banking.user_role WHERE user_id = ?", maker));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            SQLException failure = executeFailure(connection,
                    "DELETE FROM banking.app_user WHERE user_id = ?", maker);
            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("23503");
            assertThat(failure.getMessage()).contains("fk_approval_maker");
            connection.rollback();
        }

        assertThat(status(approval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.app_user WHERE user_id = ?",
                Integer.class, maker)).isEqualTo(1);
    }

    @Test
    void checkViolationsNeverMutateTheExistingValidRow() {
        long approval = insertPendingApproval(maker, Long.toString(target), "adm-tst02-check");

        assertThatThrownBy(() -> updateInTransaction(
                "UPDATE banking.approval_request SET status = 'EXPIRED' WHERE approval_id = ?", approval))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_status");
        assertThat(status(approval)).isEqualTo("PENDING");

        assertThatThrownBy(() -> updateInTransaction(
                "UPDATE banking.approval_request SET status = 'APPLIED' WHERE approval_id = ?", approval))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_applied_at");
        assertThat(status(approval)).isEqualTo("PENDING");

        assertThatThrownBy(() -> updateInTransaction(
                "UPDATE banking.approval_request SET checker_id = maker_id, status = 'REJECTED', "
                        + "decided_at = now() WHERE approval_id = ?", approval))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_maker_checker");
        assertThat(status(approval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT checker_id FROM banking.approval_request WHERE approval_id = ?", Long.class, approval))
                .isNull();
    }

    @Test
    void duplicateCompletedClaimKeepsTheOriginalReplayResponse() {
        long approval = insertPendingApproval(maker, Long.toString(target), "adm-tst02-completed");
        String key = "adm-tst02-completed-key";
        String scope = Long.toString(approval);
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, status, response_status,
                     response_body, approval_id, completed_at, expires_at)
                VALUES (?, ?, 'DECIDE_APPROVAL', ?, ?, 'COMPLETED', 200,
                        '{"status":"APPLIED"}'::jsonb, ?, now(), now() + interval '24 hours')
                """, key, maker, scope, HASH, approval));

        assertThatThrownBy(() -> updateInTransaction("""
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, status, approval_id,
                     expires_at)
                VALUES (?, ?, 'DECIDE_APPROVAL', ?, ?, 'RECEIVED', ?, now() + interval '1 hour')
                """, key, maker, scope, OTHER_HASH, approval))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_admin_idempotency_scope");

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT request_hash, status, response_status, response_body
                  FROM banking.admin_idempotency_record
                 WHERE idempotency_key = ? AND actor_id = ? AND operation = 'DECIDE_APPROVAL'
                """, key, maker);
        assertThat(stored).containsEntry("request_hash", HASH)
                .containsEntry("status", "COMPLETED")
                .containsEntry("response_status", 200);
        assertThat(jdbc.queryForObject("""
                SELECT response_body ->> 'status' FROM banking.admin_idempotency_record
                 WHERE idempotency_key = ? AND actor_id = ?
                """, String.class, key, maker)).isEqualTo("APPLIED");
    }

    // ---------------------------------------------------------------- C2 atomicity

    @Test
    void c2AuditFailureRollsBackApprovalClaimAndChallengeConsumption() {
        String key = "adm-tst02-audit";
        String challenge = verifiedProposalChallenge("AUDITOR", key);
        doThrow(new IllegalStateException("C2 audit unavailable")).when(audit).recordInTransaction(any());

        assertThatThrownBy(() -> proposeRole("AUDITOR", key, challenge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("C2 audit unavailable");

        assertThat(approvalCount()).isZero();
        assertThat(claimCount(key)).isZero();
        assertThat(challengeStatus(challenge)).isEqualTo("VERIFIED");
        assertThat(consumedAt(challenge)).isNull();
        assertThat(roleVersion(target)).isZero();
        assertThat(roleCodes(target)).doesNotContain("AUDITOR");
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isZero();

        // The failed transaction restored the proof: the same key and challenge retry commits cleanly.
        reset(audit);
        ProposalResult retried = proposeRole("AUDITOR", key, challenge);

        assertThat(retried.responseStatus()).isEqualTo(201);
        assertThat(retried.replayed()).isFalse();
        assertThat(approvalCount()).isEqualTo(1);
        assertThat(claimCount(key)).isEqualTo(1);
        assertThat(claimStatus(key)).isEqualTo("COMPLETED");
        assertThat(challengeStatus(challenge)).isEqualTo("VERIFIED");
        assertThat(consumedAt(challenge)).isNotNull();
        assertThat(pendingCountForTarget(target)).isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private ProposalResult proposeRole(String roleCode, String key, String challengeRef) {
        authenticate(maker, MAKER_AUTHORITIES);
        return proposals.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE, target, roleCode,
                null, "tst02", key, challengeRef, CORRELATION, REQUEST_ID));
    }

    private String verifiedProposalChallenge(String roleCode, String key) {
        authenticate(maker, MAKER_AUTHORITIES);
        BankingBusinessException required = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> proposals.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE, target,
                        roleCode, null, "tst02", key, null, CORRELATION, REQUEST_ID)),
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

    private long insertPendingApproval(long makerId, String targetId, String correlation) {
        Long approvalId = tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, maker_id, correlation_id)
                VALUES ('ROLE_CHANGE', 'APP_USER', ?, '{"roleCode":"AUDITOR"}'::jsonb, ?, ?)
                RETURNING approval_id
                """, Long.class, targetId, makerId, correlation));
        assertThat(approvalId).isNotNull();
        return approvalId;
    }

    private long insertPendingApproval(Connection connection, long makerId, String targetId, String correlation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, maker_id, correlation_id)
                VALUES ('ROLE_CHANGE', 'APP_USER', ?, '{"roleCode":"AUDITOR"}'::jsonb, ?, ?)
                RETURNING approval_id
                """)) {
            statement.setString(1, targetId);
            statement.setLong(2, makerId);
            statement.setString(3, correlation);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void insertClaim(String key, long actorId, String scope, String hash) {
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, status, expires_at)
                VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, ?, 'RECEIVED', now() + interval '24 hours')
                """, key, actorId, scope, hash));
    }

    private int updateInTransaction(String sql, Object... args) {
        Integer updated = tx.execute(status -> jdbc.update(sql, args));
        return updated == null ? 0 : updated;
    }

    private SQLException insertFailure(Connection connection, SqlAction action) {
        try {
            action.run();
            return null;
        } catch (SQLException failure) {
            return failure;
        }
    }

    private SQLException executeFailure(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
            statement.executeUpdate();
            return null;
        } catch (SQLException failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private int approvalCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.approval_request", Integer.class);
        return count == null ? 0 : count;
    }

    private int pendingCountForTarget(long targetUserId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM banking.approval_request
                 WHERE request_type = 'ROLE_CHANGE' AND target_type = 'APP_USER'
                   AND target_id = ? AND status = 'PENDING'
                """, Integer.class, Long.toString(targetUserId));
        return count == null ? 0 : count;
    }
    private int claimCount(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key = ?",
                Integer.class, key);
        return count == null ? 0 : count;
    }

    private String claimStatus(String key) {
        return jdbc.queryForObject(
                "SELECT status FROM banking.admin_idempotency_record WHERE idempotency_key = ?",
                String.class, key);
    }

    private String status(long approvalId) {
        return jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id = ?",
                String.class, approvalId);
    }

    private String challengeStatus(String challengeRef) {
        return jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                String.class, challengeRef);
    }

    private java.time.Instant consumedAt(String challengeRef) {
        return jdbc.queryForObject("SELECT consumed_at FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                java.time.Instant.class, challengeRef);
    }

    private long roleVersion(long userId) {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?",
                Long.class, userId);
    }

    private java.util.List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private long auditFacts(String action) {
        return auditLog.list.stream()
                .flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> "action".equals(pair.key) && action.equals(pair.value))
                .count();
    }
}
