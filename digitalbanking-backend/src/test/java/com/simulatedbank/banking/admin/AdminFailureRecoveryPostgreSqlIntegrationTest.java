package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.AdminAuditPort;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-TST-05: failure and recovery behaviour of the Admin approval workflow on real PostgreSQL 16, the real
 * transaction orchestration and the real security chain.
 *
 * <p>Faults are injected only at well-defined infrastructure boundaries, never by replacing the code under test:</p>
 * <ul>
 *   <li>PostgreSQL outage: the primary {@link DataSource} is wrapped in a switchable connector that fails new
 *       connections with {@code 08006}, the same class of failure the pool produces during an outage;</li>
 *   <li>repository write failure: a PostgreSQL trigger raises {@code 58000} on the Admin table;</li>
 *   <li>provider timeout/contention: Identity locks {@code app_user} rows in the same transaction, so a competing
 *       row lock with {@code lock_timeout} produces the real {@code 55P03} provider-side timeout;</li>
 *   <li>audit failure: the {@link AdminAuditPort} boundary throws (durable Audit provider absent, ADM-OI-07);</li>
 *   <li>lost response / crash window: the decision is committed through the service and the client retries only
 *       afterwards, so the stored idempotent response must be replayed.</li>
 * </ul>
 *
 * <p>Every failure test asserts the persisted state, not just the HTTP status: a failed attempt must leave the
 * approval {@code PENDING}, release the idempotency claim, restore the consumed step-up challenge, skip the C2
 * fact and mutate no provider state, then allow a clean retry under the same key and proof.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@ActiveProfiles("test")
@Timeout(180)
class AdminFailureRecoveryPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();
    private static final String CORRELATION = "adm-tst05-correlation";
    private static final String REQUEST_ID = "2b0d6c8e-3c4b-4d5e-9f0a-1b2c3d4e5f60";
    private static final Set<String> CHECKER_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");
    /** Step-up binds the exact decision and note, so every decision in a scenario uses the same note. */
    private static final String NOTE = "tst05 decision note";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_tst05");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.step-up-per-user-per-5-minutes", () -> "10000");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
        boundProviderLockWait();
    }

    /**
     * Role-level {@code lock_timeout} is inherited by every session the pool opens after this point. A bounded
     * wait turns Identity's in-transaction row lock into the real PostgreSQL {@code 55P03} provider timeout
     * (statement-level settings configured on the pool are not inherited by connections opened during Flyway).
     */
    private static void boundProviderLockWait() {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            connection.createStatement().execute("ALTER ROLE \"" + postgres.getUsername()
                    + "\" SET lock_timeout = '400ms'");
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot bound the provider lock wait", failure);
        }
    }

    /** Infrastructure fault seams plus the OTP transport double (notification provider absent). */
    @TestConfiguration
    static class InfrastructureFaults {
        @Bean
        @Primary
        FaultyDataSource adminFailureDataSource(DataSourceProperties properties) {
            // Build the pool lazily: the eager constructor overload would create connections before the
            // connection-init SQL is installed, and those connections would keep the unbounded lock wait.
            HikariDataSource delegate = new HikariDataSource();
            delegate.setJdbcUrl(properties.getUrl());
            delegate.setUsername(properties.getUsername());
            delegate.setPassword(properties.getPassword());
            delegate.setPoolName("AdminFailureTestPool");
            delegate.setMaximumPoolSize(6);
            delegate.setMinimumIdle(1);
            delegate.setAutoCommit(false);
            delegate.setConnectionTimeout(3000);
            delegate.setValidationTimeout(1000);

            return new FaultyDataSource(delegate);
        }

        @Bean
        OtpGateway capturingOtpGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) -> {
                OTP_BY_CHALLENGE.put(ref, new String(otp));
                return new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private FaultyDataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApprovalDecisionService decisions;

    @Autowired
    private MeterRegistry meterRegistry;


    @MockitoSpyBean
    private AdminAuditPort audit;

    private TransactionTemplate tx;
    private long maker;
    private long checker;
    private long target;
    private String makerToken;
    private String checkerToken;
    private final List<String> decisionTransactions = new CopyOnWriteArrayList<>();
    private final Map<String, Double> auditBaseline = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        dataSource.recover();
        resetFaultTriggers();
        tx = new TransactionTemplate(transactionManager);
        String suffix = unique();
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
        maker = staff("adm-tst05-maker-" + suffix, "ADMIN", false);
        checker = staff("adm-tst05-checker-" + suffix, "ADMIN", true);
        target = staff("adm-tst05-target-" + suffix, "OPERATIONS", false);
        OTP_BY_CHALLENGE.clear();
        decisionTransactions.clear();
        captureAuditBaseline();
        makerToken = bearerSession(maker);
        checkerToken = bearerSession(checker);
        clearInvocations(audit);
        reset(audit);
    }

    @AfterEach
    void tearDown() {
        dataSource.recover();
        resetFaultTriggers();
        reset(audit);
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ database outage

    @Test
    @DisplayName("TST-05: a database outage yields the documented error envelope, no partial state, and recovers")
    void databaseOutageIsReportedAndRecovers(CapturedOutput output) throws Exception {
        String correlation = "tst05-outage";
        dataSource.fail();

        HttpResponse<String> proposal = post("/api/v1/admin/roles", makerToken, correlation,
                roleBody(target), "tst05-outage-key", null);
        // Identity maps a storage failure during session resolution to the documented retryable 503
        // (07 §8.1.1/§8.1.6) before the Admin endpoint runs; the Admin contract documents 503 for every route.
        assertProblem(proposal, 503, "SERVICE_UNAVAILABLE", correlation);
        assertThat(proposal.headers().firstValue("Retry-After")).isPresent();
        assertNoSensitiveFailureDetails(proposal, output);

        assertProblem(get("/api/v1/admin/approvals", makerToken, correlation + "-read"), 503,
                "SERVICE_UNAVAILABLE", correlation + "-read");

        // Recovery: the same service answers again once PostgreSQL is reachable.
        dataSource.recover();
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
        assertThat(count("SELECT count(*) FROM banking.auth_challenge")).isZero();
        assertProblem(post("/api/v1/admin/roles", makerToken, correlation + "-recovered", roleBody(target),
                "tst05-outage-key", null), 428, "STEP_UP_REQUIRED", correlation + "-recovered");
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", maker)).isEqualTo(1);
    }

    @Test
    @DisplayName("TST-05: a connection failure inside the business transaction rolls back and the key retries")
    void connectionFailureInsideTheTransactionRollsBackAndRetries() throws Exception {
        long approvalId = proposeRole("tst05-conn-key", "tst05-conn-create");
        String challengeId = decisionChallenge(approvalId, "tst05-conn-428", "tst05-conn-verify");

        dataSource.fail();
        RuntimeException failure = catchThrowableOfType(() -> decideDirectly(approvalId, challengeId,
                "tst05-conn-decide", NOTE), RuntimeException.class);
        assertThat(failure).isNotNull();
        assertThat(hasCause(failure, org.springframework.transaction.CannotCreateTransactionException.class)
                || hasCause(failure, org.springframework.dao.DataAccessResourceFailureException.class))
                .as("the connector failure must surface as a transaction/connection failure")
                .isTrue();
        dataSource.recover();
        assertNoDecisionEffects(approvalId, challengeId);
        HttpResponse<String> applied = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-conn-retry", decisionBody("APPROVE", NOTE), "tst05-conn-decide",
                challengeId);
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(applied.body()).get("status").asText()).isEqualTo("APPLIED");
        assertThat(auditorAssignments()).isEqualTo(1);
        assertThat(claimStatus(approvalId, "tst05-conn-decide")).isEqualTo("COMPLETED");
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ repository write failure

    @Test
    @DisplayName("TST-05: an approval INSERT failure rolls back claim/challenge and the same proof retries once")
    void approvalInsertFailureRollsBackEverything(CapturedOutput output) throws Exception {
        createFaultTrigger("BEFORE INSERT");
        String body = roleBody(target);
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles", makerToken, "tst05-insert-428",
                body, "tst05-insert-key", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", "tst05-insert-428");
        String challengeId = challengeId(challengeRequired);
        verifyStepUp(makerToken, challengeRequired, challengeId, "tst05-insert-verify");

        HttpResponse<String> failed = post("/api/v1/admin/roles", makerToken, "tst05-insert-500", body,
                "tst05-insert-key", challengeId);
        assertProblem(failed, 500, "INTERNAL_ERROR", "tst05-insert-500");
        assertNoSensitiveFailureDetails(failed, output);
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isZero();
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record")).isZero();
        assertThat(challengeStatus(challengeId)).isEqualTo("VERIFIED");
        assertThat(consumedAt(challengeId)).isNull();
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isZero();

        resetFaultTriggers();
        HttpResponse<String> created = post("/api/v1/admin/roles", makerToken, "tst05-insert-201", body,
                "tst05-insert-key", challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.admin_idempotency_record WHERE status = 'COMPLETED'"))
                .isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isEqualTo(1);

        // Replay after recovery is a stored response, not a second approval.
        HttpResponse<String> replay = post("/api/v1/admin/roles", makerToken, "tst05-insert-replay", body,
                "tst05-insert-key", null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(count("SELECT count(*) FROM banking.approval_request")).isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_REQUESTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("TST-05: an approval UPDATE failure rolls back provider, claim and challenge before retry")
    void decisionUpdateFailureRollsBackProviderAndChallenge(CapturedOutput output) throws Exception {
        long approvalId = proposeRole("tst05-update-key", "tst05-update-create");
        String challengeId = decisionChallenge(approvalId, "tst05-update-428", "tst05-update-verify");
        createFaultTrigger("BEFORE UPDATE");

        HttpResponse<String> failed = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-update-500", decisionBody("APPROVE", NOTE), "tst05-update-decide",
                challengeId);
        assertProblem(failed, 500, "INTERNAL_ERROR", "tst05-update-500");
        assertNoSensitiveFailureDetails(failed, output);
        assertNoDecisionEffects(approvalId, challengeId);
        assertThat(auditorAssignments()).isZero();
        assertThat(roleVersion(target)).isZero();

        resetFaultTriggers();
        HttpResponse<String> applied = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-update-200", decisionBody("APPROVE", NOTE), "tst05-update-decide",
                challengeId);
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(approvalStatus(approvalId)).isEqualTo("APPLIED");
        assertThat(auditorAssignments()).isEqualTo(1);
        assertThat(roleVersion(target)).isEqualTo(1);
        assertThat(claimStatus(approvalId, "tst05-update-decide")).isEqualTo("COMPLETED");
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ provider timeout / crash window

    @Test
    @DisplayName("TST-05: an Identity provider lock timeout is a retryable 503 and leaves no effects")
    void providerLockTimeoutFailsClosedAndRetriesUnderTheSameProof() throws Exception {
        long approvalId = proposeRole("tst05-lock-key", "tst05-lock-create");
        String challengeId = decisionChallenge(approvalId, "tst05-lock-428", "tst05-lock-verify");

        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class))
                .as("pool connections must carry the bounded provider wait").isEqualTo("400ms");
        try (Connection blocker = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute(
                    "SELECT user_id FROM banking.app_user WHERE user_id = " + target + " FOR UPDATE");
            HttpResponse<String> timeout = post("/api/v1/admin/roles/" + approvalId + "/approve",
                    checkerToken, "tst05-lock-503", decisionBody("APPROVE", NOTE),
                    "tst05-lock-decide", challengeId);
            assertProblem(timeout, 503, "SERVICE_UNAVAILABLE", "tst05-lock-503");
            assertThat(timeout.headers().firstValue("Retry-After")).isPresent();
            assertNoDecisionEffects(approvalId, challengeId);
            assertThat(auditorAssignments()).isZero();
            blocker.rollback();
        }

        HttpResponse<String> applied = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-lock-200", decisionBody("APPROVE", NOTE), "tst05-lock-decide",
                challengeId);
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(approvalStatus(approvalId)).isEqualTo("APPLIED");
        assertThat(auditorAssignments()).isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("TST-05: a commit whose response never reached the client replays without a second effect")
    void committedDecisionReplaysWhenTheResponseWasLost() throws Exception {
        long approvalId = proposeRole("tst05-crash-key", "tst05-crash-create");
        String challengeId = decisionChallenge(approvalId, "tst05-crash-428", "tst05-crash-verify");

        // Simulates the application committing the decision and then losing the connection before the response.
        DecisionResult committed = decideDirectly(approvalId, challengeId, "tst05-crash-decide", NOTE);
        assertThat(committed.replayed()).isFalse();
        assertThat(committed.approval().status().name()).isEqualTo("APPLIED");
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);

        HttpResponse<String> replay = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-crash-replay", decisionBody("APPROVE", NOTE), "tst05-crash-decide",
                challengeId);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body()).get("status").asText()).isEqualTo("APPLIED");
        assertThat(auditorAssignments()).isEqualTo(1);
        assertThat(roleVersion(target)).isEqualTo(1);
        assertThat(claimStatus(approvalId, "tst05-crash-decide")).isEqualTo("COMPLETED");
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ audit failure

    @Test
    @DisplayName("TST-05: a C2 audit failure rolls back approval/provider/claim/challenge, then retries once")
    void auditFailureRollsBackTheWholeDecisionAndRetryEmitsOneFact(CapturedOutput output) throws Exception {
        long approvalId = proposeRole("tst05-audit-key", "tst05-audit-create");
        String challengeId = decisionChallenge(approvalId, "tst05-audit-428", "tst05-audit-verify");
        doThrow(new IllegalStateException("synthetic C2 audit provider timeout"))
                .when(audit).recordInTransaction(any());

        HttpResponse<String> failed = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-audit-500", decisionBody("APPROVE", NOTE), "tst05-audit-decide",
                challengeId);
        assertProblem(failed, 500, "INTERNAL_ERROR", "tst05-audit-500");
        assertThat(failed.body()).doesNotContain("synthetic C2 audit provider timeout");
        assertNoDecisionEffects(approvalId, challengeId);
        assertThat(auditorAssignments()).isZero();

        reset(audit);
        HttpResponse<String> applied = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-audit-200", decisionBody("APPROVE", NOTE), "tst05-audit-decide",
                challengeId);
        assertThat(applied.statusCode()).isEqualTo(200);
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);

        // A replay after the audit recovered must not duplicate the C2 fact.
        HttpResponse<String> replay = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-audit-replay", decisionBody("APPROVE", NOTE), "tst05-audit-decide",
                challengeId);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
        assertThat(output).doesNotContain("synthetic C2 audit provider timeout");
    }

    // ------------------------------------------------------------------ aborted-transaction retry

    @Test
    @DisplayName("TST-05: a retry runs in a new transaction, never inside the aborted one")
    void retryRunsInANewTransactionAfterRollback() throws Exception {
        long approvalId = proposeRole("tst05-retry-key", "tst05-retry-create");
        String challengeId = decisionChallenge(approvalId, "tst05-retry-428", "tst05-retry-verify");

        clearInvocations(audit);
        // First attempt: record the transaction identity, then fail like the audit provider would.
        doAnswer(invocation -> {
            recordTransaction();
            throw new IllegalStateException("synthetic C2 audit failure");
        }).when(audit).recordInTransaction(any());

        assertProblem(post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-retry-500", decisionBody("APPROVE", NOTE), "tst05-retry-decide",
                challengeId), 500, "INTERNAL_ERROR", "tst05-retry-500");

        assertThat(decisionTransactions).hasSize(1);
        assertThat(decisionTransactions.getFirst()).endsWith("=" + TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("no transaction may remain bound to the test thread after the rollback").isFalse();
        assertThat(decisionClaims()).isZero();
        assertThat(approvalStatus(approvalId)).isEqualTo("PENDING");
        assertThat(auditorAssignments()).isZero();

        // Second attempt: a genuine new transaction with a fresh audit write that commits.
        doAnswer(invocation -> {
            recordTransaction();
            return invocation.callRealMethod();
        }).when(audit).recordInTransaction(any());
        HttpResponse<String> applied = post("/api/v1/admin/roles/" + approvalId + "/approve", checkerToken,
                "tst05-retry-200", decisionBody("APPROVE", NOTE), "tst05-retry-decide",
                challengeId);
        assertThat(applied.statusCode()).isEqualTo(200);

        assertThat(decisionTransactions).hasSize(2);
        assertThat(decisionTransactions.get(1)).endsWith("=" + TransactionSynchronization.STATUS_COMMITTED);
        String firstTx = decisionTransactions.getFirst().split("=")[0];
        String secondTx = decisionTransactions.get(1).split("=")[0];
        assertThat(secondTx).as("the retry must not reuse the rolled-back transaction").isNotEqualTo(firstTx);
        assertThat(auditorAssignments()).isEqualTo(1);
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isEqualTo(1);
        verify(audit, org.mockito.Mockito.times(2)).recordInTransaction(any());
    }

    @Test
    @DisplayName("TST-05: non-retryable denials never open a decision transaction or create state")
    void nonRetryableFailuresAreNotRetried() throws Exception {
        long approvalId = proposeRole("tst05-noretry-key", "tst05-noretry-create");
        String challengeId = decisionChallenge(approvalId, "tst05-noretry-428", "tst05-noretry-verify");
        long operationsChecker = staff("adm-tst05-ops-checker-" + unique(), "OPERATIONS", true);
        String operationsCheckerToken = bearerSession(operationsChecker);
        // Maker who also holds CHECKER+ROLE_MANAGE: the chain passes, the service must still refuse self-approval.
        long selfMaker = staff("adm-tst05-self-maker-" + unique(), "ADMIN", true);
        String selfMakerToken = bearerSession(selfMaker);
        long selfApproval = insertPendingApproval(selfMaker, "tst05-noretry-self");
        clearInvocations(audit);

        assertProblem(post("/api/v1/admin/roles/" + approvalId + "/approve", operationsCheckerToken,
                "tst05-noretry-403", decisionBody("APPROVE", NOTE), "tst05-noretry-decide",
                challengeId), 403, "AUTH_FORBIDDEN", "tst05-noretry-403");
        assertProblem(post("/api/v1/admin/roles/9000001/approve", checkerToken, "tst05-noretry-404",
                decisionBody("APPROVE", NOTE), "tst05-noretry-decide2", null), 404,
                "APPROVAL_NOT_FOUND", "tst05-noretry-404");
        assertProblem(post("/api/v1/admin/roles/" + selfApproval + "/approve", selfMakerToken,
                "tst05-noretry-409", decisionBody("APPROVE", NOTE), "tst05-noretry-decide3", null), 409,
                "ROLE_CONFLICT", "tst05-noretry-409");

        assertThat(decisionClaims()).isZero();
        assertThat(approvalStatus(approvalId)).isEqualTo("PENDING");
        assertThat(approvalStatus(selfApproval)).isEqualTo("PENDING");
        assertThat(auditorAssignments()).isZero();
        assertThat(challengeStatus(challengeId)).isEqualTo("VERIFIED");
        verify(audit, never()).recordInTransaction(any());
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isZero();
        assertThat(auditFacts("ROLE_CHANGE_REJECTED")).isZero();
    }

    // ------------------------------------------------------------------ decision helpers

    private DecisionResult decideDirectly(long approvalId, String challengeId, String key, String note) {
        authenticateAs(checker, CHECKER_AUTHORITIES);
        try {
            return decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                    ApprovalDecision.APPROVE, note, challengeId, CORRELATION + "-direct", REQUEST_ID, key));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long proposeRole(String key, String correlation) throws Exception {
        String body = roleBody(target);
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles", makerToken, correlation + "-428",
                body, key, null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlation + "-428");
        String challengeId = challengeId(challengeRequired);
        verifyStepUp(makerToken, challengeRequired, challengeId, correlation + "-verify");
        HttpResponse<String> created = post("/api/v1/admin/roles", makerToken, correlation, body, key,
                challengeId);
        assertThat(created.statusCode()).isEqualTo(201);
        return Long.parseLong(JSON.readTree(created.body()).get("approvalId").asText());
    }

    private String decisionChallenge(long approvalId, String correlation, String verifyCorrelation)
            throws Exception {
        HttpResponse<String> challengeRequired = post("/api/v1/admin/roles/" + approvalId + "/approve",
                checkerToken, correlation, decisionBody("APPROVE", NOTE), correlation + "-issue", null);
        assertProblem(challengeRequired, 428, "STEP_UP_REQUIRED", correlation);
        String challengeId = challengeId(challengeRequired);
        verifyStepUp(checkerToken, challengeRequired, challengeId, verifyCorrelation);
        return challengeId;
    }

    private void verifyStepUp(String token, HttpResponse<String> stepUp, String challengeId,
            String correlation) throws Exception {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("captured OTP for challenge %s", challengeId).isNotNull();
        HttpResponse<String> verified = post("/api/v1/auth/step-up/verify", token, correlation,
                "{\"challengeId\":\"" + challengeId + "\",\"method\":\"OTP\",\"otp\":\"" + otp + "\"}", null,
                null);
        assertThat(verified.statusCode()).isEqualTo(200);
    }

    private void recordTransaction() {
        // Same bound connection as the business transaction, so the xid identifies the actual transaction.
        Long txid = jdbc.queryForObject("SELECT txid_current()", Long.class);
        String identity = String.valueOf(txid);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                decisionTransactions.add(identity + "=" + status);
            }
        });
    }

    private void assertNoDecisionEffects(long approvalId, String challengeId) {
        assertThat(approvalStatus(approvalId)).isEqualTo("PENDING");
        assertThat(challengeStatus(challengeId)).isEqualTo("VERIFIED");
        assertThat(consumedAt(challengeId)).isNull();
        assertThat(decisionClaims()).isZero();
        assertThat(auditFacts("ROLE_CHANGE_APPROVED")).isZero();
        assertThat(auditFacts("ROLE_CHANGE_REJECTED")).isZero();
    }

    // ------------------------------------------------------------------ fault triggers

    private void createFaultTrigger(String timing) {
        ddl("""
                CREATE OR REPLACE FUNCTION banking.tst05_fail_admin_write() RETURNS trigger
                LANGUAGE plpgsql AS $fault$
                BEGIN
                    RAISE EXCEPTION 'synthetic admin persistence failure' USING ERRCODE = '58000';
                END
                $fault$
                """);
        ddl("DROP TRIGGER IF EXISTS tst05_fail_admin_write ON banking.approval_request");
        ddl("CREATE TRIGGER tst05_fail_admin_write " + timing
                + " ON banking.approval_request FOR EACH ROW "
                + "EXECUTE FUNCTION banking.tst05_fail_admin_write()");
    }

    private void resetFaultTriggers() {
        ddl("DROP TRIGGER IF EXISTS tst05_fail_admin_write ON banking.approval_request");
        ddl("DROP FUNCTION IF EXISTS banking.tst05_fail_admin_write()");
    }

    /** DDL runs on an autocommit connection so the fault exists for every application transaction. */
    private void ddl(String sql) {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            connection.setAutoCommit(true);
            connection.createStatement().execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot apply test DDL", failure);
        }
    }

    // ------------------------------------------------------------------ seeding/assert helpers

    private long staff(String username, String roleCode, boolean checkerPrivilege) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, roleCode);
            if (checkerPrivilege) {
                jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", id);
            }
            return id;
        });
    }

    private String bearerSession(long userId) {
        String token = "tst05-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }

    private long insertPendingApproval(long makerId, String correlation) {
        String payload = """
                {"operation":"ASSIGN_ROLE","targetUserId":"%d","roleCode":"AUDITOR","privilegeCode":null,
                 "expectedVersion":0,"reason":"tst05 self approval"}
                """.formatted(target);
        return tx.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, maker_id, correlation_id)
                VALUES ('ROLE_CHANGE', 'APP_USER', ?::text, ?::jsonb, ?, ?)
                RETURNING approval_id
                """, Long.class, Long.toString(target), payload, makerId, correlation));
    }

    private String customerToken() {
        String suffix = unique();
        long userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'CUSTOMER', 'ACTIVE')",
                    "adm-tst05-customer-" + suffix);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, "adm-tst05-customer-" + suffix);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'", id);
            jdbc.update("INSERT INTO banking.customer (user_id, full_name_enc, status) "
                    + "VALUES (?, ?, 'ACTIVE')", id, new byte[] {1, 2, 3});
            return id;
        });
        return bearerSession(userId);
    }

    private void authenticateAs(long userId, Set<String> authorities) {
        var session = new SessionAuthenticator.Session(Long.toString(userId), null, authorities,
                "c".repeat(64));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private String approvalStatus(long approvalId) {
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

    private long decisionClaims() {
        return count("SELECT count(*) FROM banking.admin_idempotency_record "
                + "WHERE operation = 'DECIDE_APPROVAL'");
    }

    private String claimStatus(long approvalId, String key) {
        return jdbc.queryForObject("""
                SELECT status FROM banking.admin_idempotency_record
                 WHERE scope_key = ? AND idempotency_key = ?
                """, String.class, Long.toString(approvalId), key);
    }

    private List<String> roleCodes(long userId) {
        return jdbc.queryForList("SELECT r.role_code FROM banking.user_role ur "
                + "JOIN banking.role r ON r.role_id = ur.role_id WHERE ur.user_id = ?", String.class, userId);
    }

    private long auditorAssignments() {
        return count("SELECT count(*) FROM banking.user_role ur JOIN banking.role r ON r.role_id = ur.role_id "
                + "WHERE ur.user_id = ? AND r.role_code = 'AUDITOR'", target);
    }

    private long roleVersion(long userId) {
        return jdbc.queryForObject("SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?",
                Long.class, userId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private long auditFacts(String action) {
        double current = meterRegistry.counter("admin.audit.events", "action", action, "class", "C2",
                "outcome", "SUCCESS").count();
        double baseline = auditBaseline.getOrDefault(action, 0d);
        return Math.round(current - baseline);
    }

    private void captureAuditBaseline() {
        auditBaseline.clear();
        for (String action : List.of("ROLE_CHANGE_REQUESTED", "ROLE_CHANGE_APPROVED",
                "ROLE_CHANGE_REJECTED")) {
            auditBaseline.put(action, meterRegistry.counter("admin.audit.events", "action", action,
                    "class", "C2", "outcome", "SUCCESS").count());
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private void assertNoSensitiveFailureDetails(HttpResponse<String> response, CapturedOutput output)
            throws Exception {
        String marker = "synthetic admin persistence failure";
        assertThat(response.body()).doesNotContain(marker, "PSQLException", "org.postgresql", "at com.",
                "tst05_fail_admin_write", "password_hash", "synthetic-not-a-real-hash");
        assertThat(output).doesNotContain(marker, "synthetic-not-a-real-hash", "PSQLException",
                "org.postgresql", "at com.");
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String roleBody(long targetUserId) {
        return "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"" + targetUserId
                + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"tst05 failure injection\"}";
    }

    private static String decisionBody(String decision, String note) {
        try {
            return "{\"decision\":\"" + decision + "\",\"decisionNote\":" + JSON.writeValueAsString(note) + "}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String challengeId(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("challenge").get("challengeId").asText();
    }

    // ------------------------------------------------------------------ HTTP helpers

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "application/json");
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String correlationId, String body,
            String idempotencyKey, String stepUpChallenge) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (stepUpChallenge != null) {
            builder.header("X-Step-Up-Challenge", stepUpChallenge);
        }
        return http().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(body.get("detail").asText()).doesNotContain("Exception", "PSQLException", "org.springframework",
                "at com.");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID").orElseThrow())
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    /**
     * Switchable connector at the JDBC boundary. Failing acquisition with {@code 08006} is what a pool reports
     * during a PostgreSQL outage, so the production transaction/exception path stays untouched.
     */
    static final class FaultyDataSource implements DataSource, AutoCloseable {
        private final HikariDataSource delegate;
        private volatile boolean failing;

        FaultyDataSource(HikariDataSource delegate) {
            this.delegate = delegate;
        }

        void fail() {
            failing = true;
        }

        void recover() {
            failing = false;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(() -> delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(() -> delegate.getConnection(username, password));
        }

        private Connection connection(Connector connector) throws SQLException {
            if (failing) {
                throw new SQLTransientConnectionException("synthetic PostgreSQL outage", "08006");
            }
            return connector.open();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @FunctionalInterface
        private interface Connector {
            Connection open() throws SQLException;
        }
    }
}
