package com.simulatedbank.banking.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalAttemptRecorder;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalAttemptRecorder.Outcome;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-OPS-01: V11 attempt evidence on real PostgreSQL 16 (Flyway V1-V11).
 *
 * <p>Proves the bounded row shape (no payload/PII columns), that repeated attempts are preserved, that the write
 * survives the rollback of a surrounding business transaction ({@code REQUIRES_NEW}) and that every failure is
 * swallowed after bounded telemetry.</p>
 */
@Testcontainers
class AdminApprovalAttemptRecorderPostgreSqlIntegrationTest {

    private static final String CORRELATION = "ops-attempt-corr";
    private static final String REQUEST_ID = "5f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_ops_attempt");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;
    private static long actorId;
    private static long approvalId;

    private AdminApprovalAttemptRecorder recorder;
    private SimpleMeterRegistry registry;

    @BeforeAll
    static void migrate() {
        postgres.start();
        PostgreSqlIntegrationTestSupport.flyway(postgres).migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        actorId = insertUser("ops_attempt_maker");
        approvalId = insertApproval(actorId, "ROLE_CHANGE");
    }

    @BeforeEach
    void resetAttempts() {
        jdbc.update("DELETE FROM banking.admin_approval_attempt");
        registry = new SimpleMeterRegistry();
        recorder = new AdminApprovalAttemptRecorder(jdbc, transactionManager, Clock.systemUTC(),
                new AdminApprovalMetrics(registry), registry);
    }

    @Test
    @DisplayName("an attempt row stores only bounded codes and the table has no PII columns")
    void persistsBoundedAttemptRow() {
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, REQUEST_ID);

        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'banking' AND table_name = 'admin_approval_attempt'
                ORDER BY ordinal_position
                """, String.class);
        assertThat(columns).containsExactly(
                "attempt_id", "approval_id", "request_type", "actor_user_id", "outcome",
                "error_code", "correlation_id", "request_id", "attempted_at");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM banking.admin_approval_attempt WHERE approval_id = ?", approvalId);
        assertThat(row).containsEntry("request_type", "ROLE_CHANGE")
                .containsEntry("actor_user_id", actorId)
                .containsEntry("outcome", "PROVIDER_FAILURE")
                .containsEntry("error_code", "SERVICE_UNAVAILABLE")
                .containsEntry("correlation_id", CORRELATION)
                .containsEntry("request_id", REQUEST_ID);
        assertThat(row.get("attempted_at")).isNotNull();

        assertThat(registry.get("admin.approval.retry.attempts")
                .tag("request_type", "ROLE_CHANGE").tag("outcome", "FAILURE")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("repeated attempts for the same approval are all preserved")
    void duplicateAttemptsArePreserved() {
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.VERSION_CONFLICT,
                ErrorCode.ROLE_CONFLICT, CORRELATION, REQUEST_ID);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.VERSION_CONFLICT,
                ErrorCode.ROLE_CONFLICT, CORRELATION, REQUEST_ID);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_approval_attempt WHERE approval_id = ?",
                Integer.class, approvalId)).isEqualTo(2);
    }

    @Test
    @DisplayName("REQUIRES_NEW keeps the attempt when the surrounding business transaction rolls back")
    void attemptSurvivesBusinessRollback() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.TECHNICAL_FAILURE,
                    ErrorCode.INTERNAL_ERROR, CORRELATION, REQUEST_ID);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_approval_attempt WHERE approval_id = ?",
                Integer.class, approvalId)).isEqualTo(1);
    }

    @Test
    @DisplayName("failedAttempts counts only rows inside the requested window")
    void failedAttemptsUsesTheRequestedWindow() {
        jdbc.update("""
                INSERT INTO banking.admin_approval_attempt
                    (approval_id, request_type, actor_user_id, outcome, error_code,
                     correlation_id, request_id, attempted_at)
                VALUES (?, 'ROLE_CHANGE', ?, 'PROVIDER_FAILURE', 'SERVICE_UNAVAILABLE', ?, NULL,
                        now() - interval '2 hours')
                """, approvalId, actorId, CORRELATION);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, REQUEST_ID);

        assertThat(recorder.failedAttempts(approvalId, Instant.now().minusSeconds(3600))).isEqualTo(1);
        assertThat(recorder.failedAttempts(approvalId, Instant.now().minusSeconds(3 * 3600))).isEqualTo(2);
    }

    @Test
    @DisplayName("invalid identifiers are ignored and an invalid request id is stored as null")
    void invalidRequestIdentifiersAreBounded() {
        recorder.record(0, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, REQUEST_ID);
        recorder.record(approvalId, null, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, REQUEST_ID);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, null,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, REQUEST_ID);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                null, CORRELATION, REQUEST_ID);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, "not a correlation id", REQUEST_ID);
        recorder.record(approvalId, ApprovalRequestType.ROLE_CHANGE, actorId, Outcome.PROVIDER_FAILURE,
                ErrorCode.SERVICE_UNAVAILABLE, CORRELATION, "not-a-uuid");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_approval_attempt WHERE approval_id = ?",
                Integer.class, approvalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT request_id FROM banking.admin_approval_attempt WHERE approval_id = ?
                """, String.class, approvalId)).isNull();
    }

    @Test
    @DisplayName("a persistence failure is swallowed and counted without leaking SQL text")
    void persistenceFailureIsSwallowed() {
        assertThatCode(() -> recorder.record(999_999L, ApprovalRequestType.ROLE_CHANGE, actorId,
                Outcome.AUDIT_FAILURE, ErrorCode.AUDIT_WRITE_FAILED, CORRELATION, REQUEST_ID))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.admin_approval_attempt", Integer.class)).isZero();
        assertThat(registry.get("admin.approval.attempt.write.failures")
                .tag("request_type", "ROLE_CHANGE").counter().count()).isEqualTo(1.0);
    }

    private static long insertUser(String username) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, 'synthetic-hash', 'STAFF') RETURNING user_id
                """, Long.class, username);
    }

    private static long insertApproval(long makerId, String requestType) {
        return jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, maker_id, correlation_id)
                VALUES (?, 'APP_USER', '9001', '{}'::jsonb, ?, 'ops-attempt-approval')
                RETURNING approval_id
                """, Long.class, requestType, makerId);
    }
}
