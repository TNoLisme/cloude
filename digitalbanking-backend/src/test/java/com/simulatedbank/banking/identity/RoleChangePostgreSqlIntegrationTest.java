package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand.ApprovalState;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand.ApprovedRoleChange;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand.Operation;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand.RoleChangeResult;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumeCommand;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Admin/Audit contract fixture only; actual Identity/JPA/security/step-up/PostgreSQL runtime. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.admin.enabled=false")
@ActiveProfiles("test")
@Testcontainers
@Import(RoleChangePostgreSqlIntegrationTest.AdminFixture.class)
class RoleChangePostgreSqlIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("role_change").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @Autowired private IdentityRoleCommand roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private AdminFixture admin;
    @LocalServerPort private int port;
    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long target;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS banking.id11_approval_fixture (
                        approval_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        maker_id BIGINT NOT NULL, checker_id BIGINT NOT NULL, target_id BIGINT NOT NULL,
                        operation VARCHAR(32) NOT NULL, role_code VARCHAR(32), privilege_code VARCHAR(32),
                        expected_version BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        correlation_id VARCHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL,
                        fail_audit BOOLEAN NOT NULL DEFAULT false)
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS banking.id11_audit_fixture (
                        approval_id BIGINT PRIMARY KEY REFERENCES banking.id11_approval_fixture,
                        action VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL, target_id BIGINT NOT NULL,
                        previous_version BIGINT NOT NULL, resulting_version BIGINT NOT NULL,
                        revoked_count INT NOT NULL, correlation_id VARCHAR(64) NOT NULL,
                        request_id VARCHAR(64) NOT NULL)
                    """);
        });
        maker = user("STAFF", "ADMIN");
        checker = user("STAFF", "ADMIN");
        target = user("STAFF", "OPERATIONS");
        privilege(checker, "ACTIVE");
        authenticate(checker);
    }

    @AfterEach
    void clearActor() { SecurityContextHolder.clearContext(); }

    @Test
    void approvedAssignmentCommitsRoleVersionMetadataAndRevocationWithCaller() {
        String first = session(target), second = session(target), unrelated = session(maker);
        RoleChangeResult result = apply(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0));
        assertThat(result.previousVersion()).isZero();
        assertThat(result.resultingVersion()).isEqualTo(1);
        assertThat(result.revokedSessionCount()).isEqualTo(2);
        assertThat(result.correlationId()).isEqualTo("id11-correlation");
        assertThat(result.requestId()).isEqualTo("id11-request");
        assertThat(version()).isEqualTo(1);
        assertThat(roleCodes()).containsExactlyInAnyOrder("OPERATIONS", "AUDITOR");
        assertThat(jdbc.queryForObject("""
                SELECT assigned_by FROM banking.user_role ur JOIN banking.role r USING(role_id)
                WHERE ur.user_id=? AND r.role_code='AUDITOR'
                """, Long.class, target)).isEqualTo(checker);
        assertThat(sessionStatus(first)).isEqualTo("LOGGED_OUT");
        assertThat(sessionStatus(second)).isEqualTo("LOGGED_OUT");
        assertThat(sessionStatus(unrelated)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id=? AND logged_out_at IS NOT NULL",
                Integer.class, target)).isEqualTo(2);
    }

    @Test
    void revokeRoleRemovesOnlyApprovedRoleAndBumpsOnce() {
        apply(command(Operation.REVOKE_ROLE, "OPERATIONS", null, 0));
        assertThat(roleCodes()).isEmpty();
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void grantThenRevokePrivilegeUpdatesMetadataAndVersionWithoutDeletingHistory() {
        apply(command(Operation.GRANT_PRIVILEGE, null, "CHECKER", 0));
        assertThat(privilegeStatus(target)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT granted_by FROM banking.user_privilege WHERE user_id=?",
                Long.class, target)).isEqualTo(checker);
        apply(command(Operation.REVOKE_PRIVILEGE, null, "CHECKER", 1));
        assertThat(privilegeStatus(target)).isEqualTo("REVOKED");
        assertThat(version()).isEqualTo(2);
        apply(command(Operation.GRANT_PRIVILEGE, null, "CHECKER", 2));
        assertThat(privilegeStatus(target)).isEqualTo("ACTIVE");
        assertThat(version()).isEqualTo(3);
    }

    @Test
    void noAmbientTransactionFailsBeforeMutation() {
        assertThatThrownBy(() -> roles.applyApprovedRoleChange(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0)))
                .isInstanceOf(IllegalTransactionStateException.class);
        unchanged();
    }

    @Test
    void unauthenticatedCommandDenied() {
        SecurityContextHolder.clearContext();
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    void forgedPrincipalAuthoritiesCannotReplaceDatabasePermissions() {
        authenticate(target);
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void checkerWithoutPrivilegeDeniedDespiteStalePrincipalClaims() {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.user_privilege SET status='REVOKED' WHERE user_id=?", checker));
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.AUTH_FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveCheckerDenied(String status) {
        setStatus(checker, status);
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void makerWithoutRoleManageDenied() {
        tx.executeWithoutResult(status -> jdbc.update("DELETE FROM banking.user_role WHERE user_id=?", maker));
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void makerCannotApproveOwnRequest() {
        var c = command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0);
        reject(new ApprovedRoleChange(c.approvalId(), checker, checker, c.approvalState(), target,
                c.operation(), c.roleCode(), null, 0, c.correlationId(), c.requestId()), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void makerCannotUseApprovalToElevateSelf() {
        var c = command(Operation.GRANT_PRIVILEGE, null, "CHECKER", 0);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, c.approvalState(), maker,
                c.operation(), null, c.privilegeCode(), 0, c.correlationId(), c.requestId()), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void checkerCannotChangeOwnRoles() {
        var c = command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, c.approvalState(), checker,
                c.operation(), c.roleCode(), null, 0, c.correlationId(), c.requestId()), ErrorCode.ROLE_CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalState.class, names = {"PENDING", "REJECTED", "APPLIED", "CANCELLED"})
    void invalidApprovalStateCannotMutateRoles(ApprovalState state) {
        var c = command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, state, target, c.operation(),
                c.roleCode(), null, 0, c.correlationId(), c.requestId()),
                state == ApprovalState.PENDING ? ErrorCode.ROLE_CONFLICT : ErrorCode.APPROVAL_ALREADY_DECIDED);
    }

    @Test
    void absentTargetRejectedWithoutMutatingOtherUsers() {
        var c = command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, c.approvalState(), Long.MAX_VALUE,
                c.operation(), c.roleCode(), null, 0, c.correlationId(), c.requestId()), ErrorCode.USER_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveTargetRejected(String status) {
        setStatus(target, status);
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.ROLE_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHECKER", "SUPERADMIN", "admin"})
    void invalidRoleCatalogRejected(String code) {
        reject(command(Operation.ASSIGN_ROLE, code, null, 0), ErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void disabledRoleRejected() {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.role SET status='DISABLED' WHERE role_code='AUDITOR'"));
        try {
            reject(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0), ErrorCode.ROLE_NOT_FOUND);
        } finally {
            tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.role SET status='ACTIVE' WHERE role_code='AUDITOR'"));
        }
    }

    @Test
    void staffCannotReceiveCustomerSelfServiceRole() {
        reject(command(Operation.ASSIGN_ROLE, "CUSTOMER", null, 0), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void customerCannotReceiveStaffRoleOrCheckerPrivilege() {
        long customer = user("CUSTOMER", "CUSTOMER");
        var c = command(Operation.ASSIGN_ROLE, "ADMIN", null, 0);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, c.approvalState(), customer,
                c.operation(), "ADMIN", null, 0, c.correlationId(), c.requestId()), ErrorCode.ROLE_CONFLICT);
        reject(new ApprovedRoleChange(c.approvalId(), maker, checker, c.approvalState(), customer,
                Operation.GRANT_PRIVILEGE, null, "CHECKER", 0, c.correlationId(), c.requestId()), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void invalidPrivilegeRejected() {
        reject(command(Operation.GRANT_PRIVILEGE, null, "ADMIN", 0), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void incompatibleOperationFieldsRejected() {
        reject(command(Operation.ASSIGN_ROLE, "AUDITOR", "CHECKER", 0), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void duplicateAssignmentAndAbsentRevocationAreConflictsWithoutVersionOrSessionChanges() {
        String token = session(target);
        reject(command(Operation.ASSIGN_ROLE, "OPERATIONS", null, 0), ErrorCode.ROLE_CONFLICT);
        reject(command(Operation.REVOKE_ROLE, "AUDITOR", null, 0), ErrorCode.ROLE_CONFLICT);
        reject(command(Operation.REVOKE_PRIVILEGE, null, "CHECKER", 0), ErrorCode.ROLE_CONFLICT);
        assertThat(sessionStatus(token)).isEqualTo("ACTIVE");
    }

    @Test
    void duplicatePrivilegeGrantDoesNotIncrementVersion() {
        privilege(target, "ACTIVE");
        reject(command(Operation.GRANT_PRIVILEGE, null, "CHECKER", 0), ErrorCode.ROLE_CONFLICT);
        assertThat(privilegeStatus(target)).isEqualTo("ACTIVE");
    }

    @Test
    void staleVersionAndRetryCannotOverwriteOrRevokeNewSession() {
        var c = command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0);
        apply(c);
        String newToken = session(target);
        assertCode(() -> apply(c), ErrorCode.ROLE_CONFLICT);
        assertThat(version()).isEqualTo(1);
        assertThat(roleCodes()).containsExactlyInAnyOrder("OPERATIONS", "AUDITOR");
        assertThat(sessionStatus(newToken)).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentSameVersionHasOneCommitAndOneConflict() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("AUDITOR", "ADMIN").stream().map(role -> pool.submit(() -> {
                authenticate(checker);
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    apply(command(Operation.ASSIGN_ROLE, role, null, 0));
                    return "COMMITTED";
                } catch (BankingBusinessException error) {
                    return error.code().name();
                } finally { SecurityContextHolder.clearContext(); }
            })).toList();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "ROLE_CONFLICT");
        }
        assertThat(version()).isEqualTo(1);
        assertThat(roleCodes()).contains("OPERATIONS").hasSize(2);
    }

    @Test
    void firstLevelCachedUserCannotBypassAuthoritativeVersion() {
        tx.executeWithoutResult(status -> {
            entityManager.find(com.simulatedbank.banking.identity.adapter.out.RoleChangeUserEntity.class, target);
            jdbc.update("UPDATE banking.app_user SET role_assignment_version=3 WHERE user_id=?", target);
            assertCode(() -> roles.applyApprovedRoleChange(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0)),
                    ErrorCode.ROLE_CONFLICT);
            status.setRollbackOnly();
        });
        unchanged();
    }

    @Test
    void callerFailureRollsBackRoleVersionAndRevocation() {
        String token = session(target);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            roles.applyApprovedRoleChange(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0));
            throw new IllegalStateException("caller storage failure");
        })).isInstanceOf(IllegalStateException.class);
        unchanged();
        assertThat(sessionStatus(token)).isEqualTo("ACTIVE");
    }

    @Test
    void sessionRevocationFailureCannotCommitRoleOrApprovalOrAudit() {
        String token = session(target);
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        String constraint = "ck_id11_revoke_" + target;
        tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking.session ADD CONSTRAINT " + constraint
                + " CHECK (user_id <> " + target + " OR status='ACTIVE') NOT VALID"));
        try {
            assertThatThrownBy(() -> admin.approve(approval, challenge)).isInstanceOf(RuntimeException.class);
            unchanged();
            assertFixtureUnchanged(approval, challenge);
            assertThat(sessionStatus(token)).isEqualTo("ACTIVE");
        } finally {
            tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking.session DROP CONSTRAINT " + constraint));
        }
    }

    @Test
    void adminFixtureCommitsApprovalStepUpRoleRevocationAndAuditTogether() {
        session(target);
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        RoleChangeResult result = admin.approve(approval, challenge);
        assertThat(approvalStatus(approval)).isEqualTo("APPLIED");
        assertThat(consumed(challenge)).isTrue();
        assertThat(version()).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM banking.id11_audit_fixture WHERE approval_id=?", approval);
        assertThat(audit).containsEntry("action", "ROLE_CHANGE_APPROVED")
                .containsEntry("actor_id", checker).containsEntry("target_id", target)
                .containsEntry("previous_version", 0L).containsEntry("resulting_version", 1L)
                .containsEntry("revoked_count", 1).containsEntry("correlation_id", result.correlationId())
                .containsEntry("request_id", result.requestId());
        assertCode(() -> admin.approve(approval, challenge), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.id11_audit_fixture WHERE approval_id=?",
                Integer.class, approval)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackApprovalStepUpRolesVersionAndSessions() {
        String token = session(target);
        long approval = approval(true);
        String challenge = verifiedChallenge(approval);
        assertCode(() -> admin.approve(approval, challenge), ErrorCode.AUDIT_WRITE_FAILED);
        unchanged();
        assertFixtureUnchanged(approval, challenge);
        assertThat(sessionStatus(token)).isEqualTo("ACTIVE");
    }

    @Test
    void staleApprovalLeavesApprovalAndStepUpUnchangedWithoutAudit() {
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.app_user SET role_assignment_version=1 WHERE user_id=?", target));
        assertCode(() -> admin.approve(approval, challenge), ErrorCode.ROLE_CONFLICT);
        assertFixtureUnchanged(approval, challenge);
        assertThat(roleCodes()).containsExactly("OPERATIONS");
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void wrongStepUpActorCannotCommitApprovedChange() {
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        tx.executeWithoutResult(status -> jdbc.update("UPDATE banking.auth_challenge SET user_id=? WHERE challenge_ref=?::uuid", maker, challenge));
        assertCode(() -> admin.approve(approval, challenge), ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        unchanged();
        assertFixtureUnchanged(approval, challenge);
    }

    @Test
    void revokedOpaqueBearerAndCookieDeniedThroughRealHttpAndCannotRefresh() throws Exception {
        String affected = session(target), other = session(maker);
        assertThat(getSession(affected, false).statusCode()).isEqualTo(200);
        apply(command(Operation.ASSIGN_ROLE, "AUDITOR", null, 0));
        for (boolean cookie : List.of(false, true)) {
            var response = getSession(affected, cookie);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("AUTH_SESSION_EXPIRED");
            assertThat(response.body()).doesNotContain(affected);
        }
        assertThat(sessionStatus(affected)).isEqualTo("LOGGED_OUT");
        assertThat(getSession(other, false).statusCode()).isEqualTo(200);
    }

    @Test
    void realHttpAdminAuthorizationAndDuplicateApprovalBehavior() throws Exception {
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        var anonymous = httpApprove(approval, challenge, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var unauthorized = httpApprove(approval, challenge, session(target));
        assertThat(unauthorized.statusCode()).isEqualTo(403);
        assertFixtureUnchanged(approval, challenge);
        String checkerToken = session(checker);
        var success = httpApprove(approval, challenge, checkerToken);
        assertThat(success.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(success.body());
        assertThat(body.path("resultingVersion").asLong()).isEqualTo(1);
        var duplicate = httpApprove(approval, challenge, checkerToken);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(duplicate.body()).path("code").asText()).isEqualTo("APPROVAL_ALREADY_DECIDED");
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void adminMakerCapturesVersionThroughIdentityApiWithoutDirectTableAccess() {
        authenticate(maker);
        assertThat(roles.getRoleAssignmentVersion(target)).isZero();
        authenticate(target);
        assertCode(() -> roles.getRoleAssignmentVersion(maker), ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void forwardMigrationPreservesExistingUsersAndRejectsNegativeAssignmentVersion() {
        String database = "id11_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var adminDb = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        adminDb.execute("CREATE DATABASE " + database);
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(url,
                    postgres.getUsername(), postgres.getPassword());
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas("banking").defaultSchema("banking")
                    .target("3").load().migrate();
            var legacy = new JdbcTemplate(source);
            long id = legacy.queryForObject("""
                    INSERT INTO banking.app_user(username,password_hash,user_type)
                    VALUES('legacy-role-version','existing-hash','STAFF') RETURNING user_id
                    """, Long.class);
            legacy.update("INSERT INTO banking.user_role(user_id,role_id) SELECT ?,role_id FROM banking.role WHERE role_code='ADMIN'", id);
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas("banking").defaultSchema("banking").load().migrate();
            assertThat(legacy.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id=?", Long.class, id)).isZero();
            assertThat(legacy.queryForObject("SELECT password_hash FROM banking.app_user WHERE user_id=?", String.class, id)).isEqualTo("existing-hash");
            assertThat(legacy.queryForObject("SELECT count(*) FROM banking.user_role WHERE user_id=?", Integer.class, id)).isEqualTo(1);
            assertThatThrownBy(() -> legacy.update("UPDATE banking.app_user SET role_assignment_version=-1 WHERE user_id=?", id))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            adminDb.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    @Test
    void concurrentCheckersCannotApplySameApprovalOrAuditTwice() throws Exception {
        long approval = approval(false);
        String challenge = verifiedChallenge(approval);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                authenticate(checker);
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    admin.approve(approval, challenge);
                    return "COMMITTED";
                } catch (BankingBusinessException error) {
                    return error.code().name();
                } finally { SecurityContextHolder.clearContext(); }
            })).toList();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "APPROVAL_ALREADY_DECIDED");
        }
        assertThat(version()).isEqualTo(1);
        assertThat(approvalStatus(approval)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.id11_audit_fixture WHERE approval_id=?",
                Integer.class, approval)).isEqualTo(1);
    }

    @Test
    void assignmentWriteFailureRestoresApprovalChallengeVersionAndSessions() {
        String token = session(target);
        long approval = approval(true); // Identity must surface the write error before caller's final Audit phase.
        String challenge = verifiedChallenge(approval);
        String constraint = "ck_id11_assignment_" + target;
        tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking.user_role ADD CONSTRAINT " + constraint
                + " CHECK (user_id <> " + target + ") NOT VALID"));
        try {
            assertThatThrownBy(() -> admin.approve(approval, challenge))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            unchanged();
            assertFixtureUnchanged(approval, challenge);
            assertThat(sessionStatus(token)).isEqualTo("ACTIVE");
        } finally {
            tx.executeWithoutResult(status -> jdbc.execute("ALTER TABLE banking.user_role DROP CONSTRAINT " + constraint));
        }
    }

    private ApprovedRoleChange command(Operation op, String role, String privilege, long version) {
        return new ApprovedRoleChange(1, maker, checker, ApprovalState.APPROVED, target, op, role, privilege,
                version, "id11-correlation", "id11-request");
    }
    private RoleChangeResult apply(ApprovedRoleChange command) {
        return tx.execute(status -> roles.applyApprovedRoleChange(command));
    }
    private void reject(ApprovedRoleChange command, ErrorCode code) {
        assertCode(() -> apply(command), code);
        unchanged();
    }
    private void unchanged() {
        assertThat(version()).isZero();
        assertThat(roleCodes()).containsExactly("OPERATIONS");
    }
    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
    private long user(String type, String role) {
        return tx.execute(status -> {
            long id = jdbc.queryForObject("INSERT INTO banking.app_user(username,password_hash,user_type) VALUES(?,'external-hash',?) RETURNING user_id",
                    Long.class, "id11-" + UUID.randomUUID(), type);
            jdbc.update("INSERT INTO banking.user_role(user_id,role_id) SELECT ?,role_id FROM banking.role WHERE role_code=?", id, role);
            return id;
        });
    }
    private void privilege(long userId, String status) {
        tx.executeWithoutResult(ignored -> jdbc.update("INSERT INTO banking.user_privilege(user_id,privilege_code,status) VALUES(?,'CHECKER',?)", userId, status));
    }
    private void setStatus(long userId, String status) {
        tx.executeWithoutResult(ignored -> jdbc.update("UPDATE banking.app_user SET status=? WHERE user_id=?", status, userId));
    }
    private long version() {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id=?", Long.class, target);
    }
    private List<String> roleCodes() {
        return jdbc.queryForList("SELECT role_code FROM banking.user_role JOIN banking.role USING(role_id) WHERE user_id=? ORDER BY role_code", String.class, target);
    }
    private String privilegeStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM banking.user_privilege WHERE user_id=? AND privilege_code='CHECKER'", String.class, userId);
    }
    private String session(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "") + "testopaque";
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.session(session_token_hash,csrf_token_hash,user_id,idle_expires_at,absolute_expires_at)
                VALUES(?,?,?,now()+interval '10 minutes',now()+interval '8 hours')
                """, HmacService.sha256Hex(token), HASH, userId));
        return token;
    }
    private String sessionStatus(String token) {
        return jdbc.queryForObject("SELECT status FROM banking.session WHERE session_token_hash=?", String.class, HmacService.sha256Hex(token));
    }
    private void authenticate(long userId) {
        var claims = Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");
        var session = new SessionAuthenticator.Session(Long.toString(userId), null, claims, HASH);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(session,
                null, claims.stream().map(SimpleGrantedAuthority::new).toList()));
    }
    private long approval(boolean failAudit) {
        return tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.id11_approval_fixture(maker_id,checker_id,target_id,operation,role_code,
                    expected_version,correlation_id,request_id,fail_audit)
                VALUES(?,?,?,'ASSIGN_ROLE','AUDITOR',0,'id11-correlation','id11-request',?) RETURNING approval_id
                """, Long.class, maker, checker, target, failAudit));
    }
    private String verifiedChallenge(long approval) {
        String ref = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO banking.auth_challenge(challenge_ref,user_id,purpose,method,channel,otp_hash,
                    request_hash,idempotency_key,status,verified_at,expires_at,correlation_id)
                VALUES(?::uuid,?,'SENSITIVE_ACTION','OTP','SMS','external-verifier',?,?,'VERIFIED',
                    now(),now()+interval '5 minutes','id11-correlation')
                """, ref, checker, HASH, Long.toString(approval)));
        return ref;
    }
    private String approvalStatus(long approval) {
        return jdbc.queryForObject("SELECT status FROM banking.id11_approval_fixture WHERE approval_id=?", String.class, approval);
    }
    private boolean consumed(String ref) {
        return jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM banking.auth_challenge WHERE challenge_ref=?::uuid", Boolean.class, ref);
    }
    private void assertFixtureUnchanged(long approval, String challenge) {
        assertThat(approvalStatus(approval)).isEqualTo("PENDING");
        assertThat(consumed(challenge)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.id11_audit_fixture WHERE approval_id=?", Integer.class, approval)).isZero();
    }
    private HttpResponse<String> getSession(String token, boolean cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/session"))
                .header(cookie ? "Cookie" : "Authorization", cookie ? "DB_SESSION=" + token : "Bearer " + token)
                .header("X-Role", "ADMIN").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> httpApprove(long approval, String challenge, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/roles/" + approval + "/approve"))
                .header("X-Step-Up-Challenge", challenge).header("X-Role", "ADMIN")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }


    /** Not a production Admin endpoint or durable Audit provider; deliberately test-only contract consumer. */
    @RestController
    @TestConfiguration(proxyBeanMethods = false)
    static class AdminFixture {
        private final IdentityRoleCommand roles;
        private final StepUpChallengePort challenges;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        AdminFixture(IdentityRoleCommand roles, StepUpChallengePort challenges, JdbcTemplate jdbc,
                PlatformTransactionManager manager) {
            this.roles = roles; this.challenges = challenges; this.jdbc = jdbc;
            this.tx = new TransactionTemplate(manager);
        }
        @PostMapping("/api/v1/admin/roles/{id}/approve")
        public RoleChangeResult approve(@PathVariable long id, @RequestHeader("X-Step-Up-Challenge") String ref) {
            return tx.execute(status -> {
                Map<String, Object> approval = jdbc.queryForMap("SELECT * FROM banking.id11_approval_fixture WHERE approval_id=? FOR UPDATE", id);
                if (!"PENDING".equals(approval.get("status"))) throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
                long checker = ((Number) approval.get("checker_id")).longValue();
                challenges.consumeVerified(new ConsumeCommand(ref, checker, ChallengePurpose.SENSITIVE_ACTION, HASH, Long.toString(id)));
                jdbc.update("UPDATE banking.id11_approval_fixture SET status='APPROVED' WHERE approval_id=?", id);
                RoleChangeResult result = roles.applyApprovedRoleChange(new ApprovedRoleChange(id,
                        ((Number) approval.get("maker_id")).longValue(), checker, ApprovalState.APPROVED,
                        ((Number) approval.get("target_id")).longValue(), Operation.valueOf(approval.get("operation").toString()),
                        (String) approval.get("role_code"), (String) approval.get("privilege_code"),
                        ((Number) approval.get("expected_version")).longValue(), (String) approval.get("correlation_id"),
                        (String) approval.get("request_id")));
                jdbc.update("UPDATE banking.id11_approval_fixture SET status='APPLIED' WHERE approval_id=?", id);
                if (Boolean.TRUE.equals(approval.get("fail_audit"))) throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED);
                jdbc.update("""
                        INSERT INTO banking.id11_audit_fixture VALUES(?, 'ROLE_CHANGE_APPROVED', ?, ?, ?, ?, ?, ?, ?)
                        """, id, checker, result.targetUserId(), result.previousVersion(), result.resultingVersion(),
                        result.revokedSessionCount(), result.correlationId(), result.requestId());
                return result;
            });
        }
    }
}
