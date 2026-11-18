package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.simulatedbank.banking.BackendApplication;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.VerifyCommand;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-TST-05 §14: idempotent replay after a full application recovery.
 *
 * <p>Two independent application contexts share only PostgreSQL. Context 1 executes a complete
 * proposal-and-decision flow; its beans are then destroyed. Context 2 (fresh beans, no in-memory state) replays the
 * same decision key and must answer with the stored response, without a second provider mutation, a second
 * approval transition or a second C2 audit fact. This is the real "retry after the application restarted"
 * scenario behind a crash window, not a mocked repository.</p>
 */
@Testcontainers
class AdminIdempotencyRestartPostgreSqlIntegrationTest {

    private static final Map<String, String> OTP_BY_CHALLENGE = new ConcurrentHashMap<>();
    private static final String NOTE = "restart decision note";
    private static final String DECISION_KEY = "restart-decision-key";
    private static final String CORRELATION = "adm-restart-correlation";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_restart");

    @Test
    void decisionReplaySurvivesApplicationRestartWithoutDuplicateEffects() {
        ConfigurableApplicationContext first = startApplication();
        Fixture fixture;
        Map<String, Object> storedBody;
        try {
            DataSource dataSource = first.getBean(DataSource.class);
            PostgreSqlIntegrationTestSupport.assertContainerDataSource(dataSource, postgres);
            fixture = seed(dataSource);

            ProposalResult proposal = propose(first, fixture);
            assertThat(proposal.responseStatus()).isEqualTo(201);
            fixture.approvalId = proposal.approvalId();

            DecisionResult decision = decide(first, fixture, proposal.approvalId());
            assertThat(decision.responseStatus()).isEqualTo(200);
            assertThat(decision.replayed()).isFalse();
            storedBody = decision.responseBody();
            assertThat(storedBody.get("status")).isEqualTo("APPLIED");

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM banking.approval_request WHERE approval_id = ?", String.class,
                    proposal.approvalId())).isEqualTo("APPLIED");
            assertThat(roleVersion(jdbc, fixture.target())).isEqualTo(1);
            assertThat(auditEvents(first, "ROLE_CHANGE_APPROVED")).isEqualTo(1);
        } finally {
            first.close();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            DataSource dataSource = second.getBean(DataSource.class);
            PostgreSqlIntegrationTestSupport.assertContainerDataSource(dataSource, postgres);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // The session survives the restart through the shared schema.
            assertThat(second.getBean(SessionAuthenticator.class).authenticate(fixture.checkerToken()))
                    .get()
                    .satisfies(session -> assertThat(session.userId())
                            .isEqualTo(Long.toString(fixture.checker())));

            // Same key, same decision, same note: the stored response is replayed and nothing re-executes.
            DecisionResult replay = decide(second, fixture, fixture.approvalId());
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.responseStatus()).isEqualTo(200);
            assertThat(replay.responseBody()).isEqualTo(storedBody);
            assertThat(auditEvents(second, "ROLE_CHANGE_APPROVED")).isZero();
            assertThat(roleVersion(jdbc, fixture.target())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.user_role ur JOIN banking.role r ON r.role_id = ur.role_id "
                            + "WHERE ur.user_id = ? AND r.role_code = 'AUDITOR'", Integer.class,
                    fixture.target())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM banking.admin_idempotency_record WHERE idempotency_key = ?",
                    String.class, DECISION_KEY)).isEqualTo("COMPLETED");

            // A different key cannot duplicate the terminal transition either.
            SecurityContextHolder.clearContext();
            authenticate(new SessionAuthenticator.Session(Long.toString(fixture.checker()), null,
                    checkerAuthorities(), "0".repeat(64)));
            BankingBusinessException conflict = catchThrowableOfType(() -> second
                    .getBean(ApprovalDecisionService.class)
                    .decide(new DecisionCommand(fixture.approvalId(), ApprovalRequestType.ROLE_CHANGE,
                            ApprovalDecision.APPROVE, NOTE, fixture.decisionChallenge(), CORRELATION,
                            null, "restart-other-key")), BankingBusinessException.class);
            assertThat(conflict).isNotNull();
            assertThat(conflict.code()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED);
            assertThat(roleVersion(jdbc, fixture.target())).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ flow

    private ProposalResult propose(ConfigurableApplicationContext context, Fixture fixture) {
        ApprovalProposalService proposals = context.getBean(ApprovalProposalService.class);
        StepUpChallengePort challenges = context.getBean(StepUpChallengePort.class);
        SecurityContextHolder.clearContext();
        authenticate(new SessionAuthenticator.Session(Long.toString(fixture.maker()), null,
                Set.of("ROLE_ADMIN", "ROLE_MANAGE"), "0".repeat(64)));

        BankingBusinessException required = catchThrowableOfType(() -> proposals.proposeRole(new RoleProposalCommand(
                RoleChangeOperation.ASSIGN_ROLE, fixture.target(), "AUDITOR", null, "restart proposal",
                "restart-proposal-key", null, CORRELATION, null)), BankingBusinessException.class);
        assertThat(required).isNotNull();
        assertThat(required.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
        String challengeId = challengeId(required);
        verify(challenges, challengeId, fixture.maker());

        return proposals.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE, fixture.target(),
                "AUDITOR", null, "restart proposal", "restart-proposal-key", challengeId, CORRELATION, null));
    }

    private DecisionResult decide(ConfigurableApplicationContext context, Fixture fixture, long approvalId) {
        ApprovalDecisionService decisions = context.getBean(ApprovalDecisionService.class);
        StepUpChallengePort challenges = context.getBean(StepUpChallengePort.class);
        SecurityContextHolder.clearContext();
        authenticate(new SessionAuthenticator.Session(Long.toString(fixture.checker()), null,
                checkerAuthorities(), "0".repeat(64)));
        String challengeId = fixture.decisionChallenge();
        if (challengeId == null) {
            BankingBusinessException required = catchThrowableOfType(() -> decisions.decide(new DecisionCommand(
                    approvalId, ApprovalRequestType.ROLE_CHANGE, ApprovalDecision.APPROVE, NOTE, null,
                    CORRELATION, null, DECISION_KEY)), BankingBusinessException.class);
            assertThat(required).isNotNull();
            assertThat(required.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
            challengeId = challengeId(required);
            verify(challenges, challengeId, fixture.checker());
            fixture.decisionChallenge = challengeId;
        }
        return decisions.decide(new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalDecision.APPROVE, NOTE, challengeId, CORRELATION, null, DECISION_KEY));
    }

    private void verify(StepUpChallengePort challenges, String challengeId, long userId) {
        String otp = OTP_BY_CHALLENGE.get(challengeId);
        assertThat(otp).as("captured OTP for challenge %s", challengeId).isNotNull();
        challenges.verifyChallenge(new VerifyCommand(challengeId, userId, ChallengePurpose.SENSITIVE_ACTION,
                otp.toCharArray(), CORRELATION));
    }

    private static Set<String> checkerAuthorities() {
        return Set.of("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");
    }

    @SuppressWarnings("unchecked")
    private static String challengeId(BankingBusinessException exception) {
        Map<String, Object> challenge = (Map<String, Object>) exception.extensions().get("challenge");
        assertThat(challenge).isNotNull();
        return challenge.get("challengeId").toString();
    }

    private static void authenticate(SessionAuthenticator.Session session) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, session.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private static long auditEvents(ConfigurableApplicationContext context, String action) {
        return Math.round(context.getBean(MeterRegistry.class)
                .counter("admin.audit.events", "action", action, "class", "C2", "outcome", "SUCCESS")
                .count());
    }

    // ------------------------------------------------------------------ fixture/bootstrap

    private static ConfigurableApplicationContext startApplication() {
        Map<String, Object> properties = PostgreSqlIntegrationTestSupport.springApplicationProperties(postgres);
        properties.put("spring.main.register-shutdown-hook", "false");
        properties.put("spring.datasource.hikari.maximum-pool-size", "6");
        properties.put("spring.datasource.hikari.minimum-idle", "1");
        properties.put("app.identity.otp-expiry-sweep-delay", "PT24H");
        properties.put("app.security.rate-limit.read-per-user-per-minute", "10000");
        return new SpringApplicationBuilder(BackendApplication.class, OtpCaptureConfiguration.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("adm-tst05-restart", properties)))
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    static class OtpCaptureConfiguration {
        /** The notification provider does not exist yet; only its transport seam is replaced. */
        @Bean
        OtpGateway capturingOtpGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) -> {
                OTP_BY_CHALLENGE.put(ref, new String(otp));
                return new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
            };
        }
    }

    private static Fixture seed(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long maker = staff(tx, jdbc, "adm-restart-maker-" + suffix, "ADMIN", false);
        long checker = staff(tx, jdbc, "adm-restart-checker-" + suffix, "ADMIN", true);
        long target = staff(tx, jdbc, "adm-restart-target-" + suffix, "OPERATIONS", false);
        String makerToken = session(tx, jdbc, maker);
        String checkerToken = session(tx, jdbc, checker);
        OTP_BY_CHALLENGE.clear();
        return new Fixture(maker, checker, target, makerToken, checkerToken);
    }

    private static long staff(TransactionTemplate tx, JdbcTemplate jdbc, String username, String roleCode,
            boolean checker) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, 'synthetic-not-a-real-hash', 'STAFF', 'ACTIVE')", username);
            long id = jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, roleCode);
            if (checker) {
                jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code, status) "
                        + "VALUES (?, 'CHECKER', 'ACTIVE')", id);
            }
            return id;
        });
    }

    private static String session(TransactionTemplate tx, JdbcTemplate jdbc, long userId) {
        String token = "adm-restart-" + UUID.randomUUID();
        tx.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')",
                HmacService.sha256Hex(token), "c".repeat(64), userId));
        return token;
    }

    private static long roleVersion(JdbcTemplate jdbc, long userId) {
        Long value = jdbc.queryForObject(
                "SELECT role_assignment_version FROM banking.app_user WHERE user_id = ?", Long.class, userId);
        return value == null ? 0 : value;
    }

    /** Mutable holder so the decision challenge created in context 1 is reused by the context-2 replay. */
    private static final class Fixture {
        private final long maker;
        private final long checker;
        private final long target;
        private final String makerToken;
        private final String checkerToken;
        private String decisionChallenge;
        private long approvalId;

        Fixture(long maker, long checker, long target, String makerToken, String checkerToken) {
            this.maker = maker;
            this.checker = checker;
            this.target = target;
            this.makerToken = makerToken;
            this.checkerToken = checkerToken;
        }

        long maker() {
            return maker;
        }

        long checker() {
            return checker;
        }

        long target() {
            return target;
        }

        String makerToken() {
            return makerToken;
        }

        String checkerToken() {
            return checkerToken;
        }

        String decisionChallenge() {
            return decisionChallenge;
        }

        long approvalId() {
            return approvalId;
        }
    }
}
