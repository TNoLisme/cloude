package com.simulatedbank.banking.admin.application.ops;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-ADM-OPS-01: read-only detection of stuck / retry-exhausted Admin approvals.
 *
 * <p>The scan only reads {@code banking.approval_request} and {@code banking.admin_approval_attempt}. It never
 * updates the approval status, never consumes a step-up challenge and never calls Identity/Limit/Audit providers.
 * Manual recovery stays the normal authorized checker decision flow (re-run the approval with a fresh
 * {@code Idempotency-Key} and step-up proof); this service only produces the evidence used by alerts.</p>
 *
 * <p>{@code stuck} is every {@code PENDING} approval older than {@code app.admin.ops.stuck-after};
 * {@code exhausted} is the subset whose failed attempts inside {@code app.admin.ops.attempt-window} reach
 * {@code app.admin.ops.max-failed-attempts}.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminApprovalRecoveryService {

    public record StuckApproval(long approvalId, ApprovalRequestType requestType, Instant createdAt,
            int failedAttempts) {
    }

    public record RecoveryReport(int pendingScanned, List<StuckApproval> stuck,
            List<StuckApproval> exhausted) {
    }

    private static final List<ApprovalRequestType> METERED_TYPES =
            List.of(ApprovalRequestType.LIMIT_CHANGE, ApprovalRequestType.ROLE_CHANGE);

    private static final String SCAN_SQL = """
            SELECT a.approval_id, a.request_type, a.created_at, a.correlation_id,
                   COALESCE(t.failed_attempts, 0) AS failed_attempts
            FROM banking.approval_request a
            LEFT JOIN (
                SELECT approval_id, count(*) AS failed_attempts
                FROM banking.admin_approval_attempt
                WHERE attempted_at >= ?
                GROUP BY approval_id
            ) t ON t.approval_id = a.approval_id
            WHERE a.status = 'PENDING'
              AND a.created_at < ?
              AND a.request_type IN ('LIMIT_CHANGE', 'ROLE_CHANGE')
            ORDER BY a.created_at ASC, a.approval_id ASC
            """;

    private final JdbcTemplate jdbc;
    private final AdminOpsProperties properties;
    private final Clock clock;
    private final AdminApprovalMetrics metrics;
    private final AdminWorkflowLog workflow;

    public AdminApprovalRecoveryService(JdbcTemplate jdbc, AdminOpsProperties properties, Clock clock,
            AdminApprovalMetrics metrics, AdminWorkflowLog workflow) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.workflow = workflow;
    }

    @Transactional(readOnly = true)
    public RecoveryReport scan() {
        Instant now = clock.instant();
        Instant attemptSince = now.minus(properties.attemptWindow());
        Instant stuckBefore = now.minus(properties.stuckAfter());
        List<ScanRow> rows = jdbc.query(SCAN_SQL, (resultSet, index) -> new ScanRow(
                        resultSet.getLong("approval_id"),
                        ApprovalRequestType.valueOf(resultSet.getString("request_type")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("correlation_id"),
                        resultSet.getInt("failed_attempts")),
                Timestamp.from(attemptSince), Timestamp.from(stuckBefore));

        List<StuckApproval> stuck = new ArrayList<>(rows.size());
        List<StuckApproval> exhausted = new ArrayList<>();
        Map<ApprovalRequestType, Integer> perType = new EnumMap<>(ApprovalRequestType.class);
        for (ScanRow row : rows) {
            StuckApproval approval = new StuckApproval(
                    row.approvalId(), row.requestType(), row.createdAt(), row.failedAttempts());
            stuck.add(approval);
            perType.merge(row.requestType(), 1, Integer::sum);
            String ageBucket = ageBucket(now, row.createdAt());
            workflow.recovery("STUCK_APPROVAL", row.requestType(), "FAILURE", row.failedAttempts(),
                    ageBucket, row.correlationId());
            if (row.failedAttempts() >= properties.maxFailedAttempts()) {
                exhausted.add(approval);
                workflow.recovery("RECOVERY_EXHAUSTED", row.requestType(), "EXHAUSTED",
                        row.failedAttempts(), ageBucket, row.correlationId());
                metrics.retryExhausted(row.requestType().name());
            }
        }

        for (ApprovalRequestType type : METERED_TYPES) {
            metrics.stuck(type.name(), perType.getOrDefault(type, 0));
            metrics.recovery(type.name(), "SUCCESS");
        }
        return new RecoveryReport(rows.size(), List.copyOf(stuck), List.copyOf(exhausted));
    }

    /**
     * Best-effort recovery evidence after a checker decision commits: a decision that succeeds while prior
     * failed attempts exist inside the attempt window is a manual recovery of a stuck approval. No state is
     * mutated here and no provider is called; the normal authorized decision already applied the change.
     */
    public void recordSuccessfulDecision(long approvalId, ApprovalRequestType requestType) {
        if (approvalId <= 0 || requestType == null) {
            return;
        }
        try {
            Instant since = clock.instant().minus(properties.attemptWindow());
            Integer failed = jdbc.queryForObject(
                    "SELECT count(*) FROM banking.admin_approval_attempt "
                            + "WHERE approval_id = ? AND attempted_at >= ?",
                    Integer.class, approvalId, Timestamp.from(since));
            if (failed != null && failed > 0) {
                metrics.recovery(requestType.name(), "SUCCESS");
                workflow.recovery("RECOVERY_APPLIED", requestType, "SUCCESS", failed, null, null);
                return;
            }
            workflow.transition("DECISION", requestType, null, "SUCCESS", null,
                    Long.toString(approvalId), null, null);
        } catch (RuntimeException loggingFailure) {
            // Telemetry only: the committed decision is never affected by recovery evidence.
        }
    }

    private static String ageBucket(Instant now, Instant createdAt) {
        Duration age = Duration.between(createdAt, now);
        if (age.compareTo(Duration.ofMinutes(5)) < 0) {
            return "LT_5M";
        }
        if (age.compareTo(Duration.ofMinutes(30)) < 0) {
            return "5M_30M";
        }
        if (age.compareTo(Duration.ofHours(1)) < 0) {
            return "30M_1H";
        }
        if (age.compareTo(Duration.ofHours(6)) < 0) {
            return "1H_6H";
        }
        if (age.compareTo(Duration.ofHours(24)) < 0) {
            return "6H_24H";
        }
        return "GT_24H";
    }

    private record ScanRow(long approvalId, ApprovalRequestType requestType, Instant createdAt,
            String correlationId, int failedAttempts) {
    }
}
