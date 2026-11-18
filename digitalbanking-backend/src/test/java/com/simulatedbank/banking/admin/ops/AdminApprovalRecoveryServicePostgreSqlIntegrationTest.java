package com.simulatedbank.banking.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService.RecoveryReport;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService.StuckApproval;
import com.simulatedbank.banking.admin.application.ops.AdminOpsProperties;
import com.simulatedbank.banking.admin.application.ops.AdminWorkflowLog;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-OPS-01: the recovery sweep is read-only detection on real PostgreSQL 16 (Flyway V1-V11).
 *
 * <p>Seeds old/recent {@code PENDING} approvals, an {@code APPLIED} approval and failed attempts inside/outside the
 * attempt window, then asserts the stuck/exhausted classification, bounded gauges and — critically — that no
 * approval row, provider table or transaction state changed.</p>
 */
@Testcontainers
class AdminApprovalRecoveryServicePostgreSqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AdminOpsProperties PROPERTIES = new AdminOpsProperties(
            true, Duration.ofMinutes(30), Duration.ofHours(1), 5,
            Duration.ofMinutes(5), Duration.ofMinutes(1));

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_ops_recovery");

    private static JdbcTemplate jdbc;
    private static long makerId;
    private static long checkerId;
    private static long stuckRoleApproval;
    private static long stuckLimitApproval;
    private static long recentApproval;
    private static long appliedApproval;

    private SimpleMeterRegistry registry;
    private AdminApprovalRecoveryService service;

    @BeforeAll
    static void migrateAndSeed() {
        postgres.start();
        PostgreSqlIntegrationTestSupport.flyway(postgres).migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        makerId = insertUser("ops_recovery_maker");
        checkerId = insertUser("ops_recovery_checker");

        stuckRoleApproval = insertApproval("ROLE_CHANGE", "PENDING", NOW.minus(Duration.ofHours(2)));
        stuckLimitApproval = insertApproval("LIMIT_CHANGE", "PENDING", NOW.minus(Duration.ofMinutes(45)));
        recentApproval = insertApproval("ROLE_CHANGE", "PENDING", NOW.minus(Duration.ofMinutes(10)));
        appliedApproval = insertAppliedApproval(NOW.minus(Duration.ofHours(3)));

        // Five failures inside the window for ROLE (exhausted at maxFailedAttempts=5).
        for (int attempt = 0; attempt < 5; attempt++) {
            insertAttempt(stuckRoleApproval, "ROLE_CHANGE", NOW.minus(Duration.ofMinutes(5 - attempt)),
                    "VERSION_CONFLICT", ErrorCode.ROLE_CONFLICT);
        }
        // One inside and several outside the window for LIMIT: not exhausted.
        insertAttempt(stuckLimitApproval, "LIMIT_CHANGE", NOW.minus(Duration.ofMinutes(3)),
                "PROVIDER_FAILURE", ErrorCode.SERVICE_UNAVAILABLE);
        for (int attempt = 0; attempt < 6; attempt++) {
            insertAttempt(stuckLimitApproval, "LIMIT_CHANGE", NOW.minus(Duration.ofHours(2)),
                    "PROVIDER_FAILURE", ErrorCode.SERVICE_UNAVAILABLE);
        }
        // Recent approval is not stuck even though it has many failures.
        for (int attempt = 0; attempt < 5; attempt++) {
            insertAttempt(recentApproval, "ROLE_CHANGE", NOW.minus(Duration.ofMinutes(2)),
                    "PROVIDER_FAILURE", ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @BeforeEach
    void createService() {
        registry = new SimpleMeterRegistry();
        service = new AdminApprovalRecoveryService(jdbc, PROPERTIES, CLOCK,
                new AdminApprovalMetrics(registry), new AdminWorkflowLog());
    }

    @Test
    @DisplayName("scan classifies old PENDING approvals and their exhausted subset")
    void classifiesStuckAndExhaustedApprovals() {
        RecoveryReport report = service.scan();

        assertThat(report.pendingScanned()).isEqualTo(2);
        assertThat(report.stuck()).extracting(StuckApproval::approvalId)
                .containsExactly(stuckRoleApproval, stuckLimitApproval);
        assertThat(report.stuck()).extracting(StuckApproval::failedAttempts).containsExactly(5, 1);
        assertThat(report.stuck()).extracting(StuckApproval::requestType).containsExactly(
                ApprovalRequestType.ROLE_CHANGE, ApprovalRequestType.LIMIT_CHANGE);
        assertThat(report.exhausted()).extracting(StuckApproval::approvalId)
                .containsExactly(stuckRoleApproval);
        assertThat(report.stuck()).extracting(StuckApproval::approvalId)
                .doesNotContain(recentApproval, appliedApproval);
    }

    @Test
    @DisplayName("scan updates only bounded gauges and the exhaustion counter")
    void recordsBoundedMetrics() {
        service.scan();

        assertThat(registry.get("admin.approval.stuck")
                .tag("request_type", "ROLE_CHANGE").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.stuck")
                .tag("request_type", "LIMIT_CHANGE").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.retry.exhausted")
                .tag("request_type", "ROLE_CHANGE").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("admin.approval.retry.exhausted")
                .tag("request_type", "LIMIT_CHANGE").counter()).isNull();
        assertThat(registry.get("admin.approval.recovery")
                .tag("request_type", "ROLE_CHANGE").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("admin.approval.recovery")
                .tag("request_type", "LIMIT_CHANGE").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("scan and recordSuccessfulDecision never mutate approval or provider state")
    void scanIsReadOnly() {
        List<Map<String, Object>> approvalsBefore = approvalSnapshot();
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM banking.app_user", Integer.class);
        int limitsBefore = jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class);

        service.scan();
        assertThatCode(() -> service.recordSuccessfulDecision(stuckRoleApproval, ApprovalRequestType.ROLE_CHANGE))
                .doesNotThrowAnyException();

        assertThat(approvalSnapshot()).isEqualTo(approvalsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.app_user", Integer.class))
                .isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class))
                .isEqualTo(limitsBefore);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM banking.approval_request WHERE approval_id = ?
                """, String.class, stuckRoleApproval)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM banking.approval_request WHERE approval_id = ?
                """, String.class, appliedApproval)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("a second scan is idempotent for classification and gauge values")
    void scanIsRepeatable() {
        service.scan();
        RecoveryReport second = service.scan();

        assertThat(second.stuck()).hasSize(2);
        assertThat(second.exhausted()).hasSize(1);
        assertThat(registry.get("admin.approval.stuck")
                .tag("request_type", "ROLE_CHANGE").gauge().value()).isEqualTo(1.0);
    }

    private static List<Map<String, Object>> approvalSnapshot() {
        return jdbc.queryForList("""
                SELECT approval_id, status, decided_at, applied_at, checker_id
                FROM banking.approval_request ORDER BY approval_id
                """);
    }

    private static long insertUser(String username) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, 'synthetic-hash', 'STAFF') RETURNING user_id
                """, Long.class, username);
    }

    private static long insertApproval(String requestType, String status, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id,
                     correlation_id, created_at)
                VALUES (?, 'APP_USER', '9001', '{}'::jsonb, ?, ?, ?, ?)
                RETURNING approval_id
                """, Long.class, requestType, status, makerId, "ops-recovery-" + requestType, Timestamp.from(createdAt));
    }

    private static long insertAppliedApproval(Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     correlation_id, created_at, decided_at, applied_at)
                VALUES ('ROLE_CHANGE', 'APP_USER', '9002', '{}'::jsonb, 'APPLIED', ?, ?, ?, ?, ?, ?)
                RETURNING approval_id
                """, Long.class, makerId, checkerId, "ops-recovery-applied", Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(60)), Timestamp.from(createdAt.plusSeconds(60)));
    }

    private static void insertAttempt(long approvalId, String requestType, Instant attemptedAt,
            String outcome, ErrorCode errorCode) {
        jdbc.update("""
                INSERT INTO banking.admin_approval_attempt
                    (approval_id, request_type, actor_user_id, outcome, error_code,
                     correlation_id, request_id, attempted_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                """, approvalId, requestType, checkerId, outcome, errorCode.name(),
                "ops-recovery-attempt", Timestamp.from(attemptedAt));
    }
}
