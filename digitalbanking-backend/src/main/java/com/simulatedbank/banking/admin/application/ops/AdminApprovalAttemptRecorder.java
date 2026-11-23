package com.simulatedbank.banking.admin.application.ops;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-ADM-OPS-01: durable retry/failure evidence for checker attempts
 * ({@code banking.admin_approval_attempt}, V11).
 *
 * <p>Evidence is written in its own {@code REQUIRES_NEW} transaction so a failed business transaction can still
 * roll back while the attempt stays visible. Rows contain bounded codes only: approval id, request type, actor id,
 * attempt outcome, an {@link ErrorCode} name, a validated correlation id and an optional request id — never a
 * payload, note, PII or exception text.</p>
 *
 * <p>The recorder is best-effort by contract: invalid inputs are ignored and every persistence/metric failure is
 * swallowed after a bounded log so the original business error is never masked.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public final class AdminApprovalAttemptRecorder {

    public enum Outcome {
        PROVIDER_FAILURE, VERSION_CONFLICT, AUDIT_FAILURE, STEP_UP_FAILURE, TECHNICAL_FAILURE
    }

    private static final Logger LOG = LoggerFactory.getLogger(AdminApprovalAttemptRecorder.class);
    private static final String ATTEMPT_WRITE_FAILURES = "admin.approval.attempt.write.failures";

    private static final String INSERT_SQL = """
            INSERT INTO banking.admin_approval_attempt
                (approval_id, request_type, actor_user_id, outcome, error_code,
                 correlation_id, request_id, attempted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String COUNT_SQL = """
            SELECT count(*) FROM banking.admin_approval_attempt
            WHERE approval_id = ? AND attempted_at >= ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final AdminApprovalMetrics metrics;
    private final MeterRegistry meterRegistry;

    public AdminApprovalAttemptRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            Clock clock, AdminApprovalMetrics metrics, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.metrics = metrics;
        this.meterRegistry = meterRegistry;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(long approvalId, ApprovalRequestType requestType, long actorUserId,
            Outcome outcome, ErrorCode errorCode, String correlationId, String requestId) {
        if (approvalId <= 0 || requestType == null || !requestType.openInMvp() || actorUserId <= 0
                || outcome == null || errorCode == null || !RequestContext.isValidCorrelationId(correlationId)) {
            return;
        }
        String safeRequestId = validRequestId(requestId) ? requestId : null;
        try {
            Instant attemptedAt = clock.instant();
            requiresNew.executeWithoutResult(status -> jdbc.update(INSERT_SQL,
                    approvalId, requestType.name(), actorUserId, outcome.name(), errorCode.name(),
                    correlationId, safeRequestId, Timestamp.from(attemptedAt)));
            metrics.retryAttempt(requestType.name(), metricOutcome(outcome));
        } catch (RuntimeException persistenceFailure) {
            // No SQL, identifiers or exception text: only the bounded request type/outcome pair.
            attemptWriteFailure(requestType.name());
            LOG.error("ADMIN_ATTEMPT_WRITE_FAILED request_type={} outcome={}",
                    requestType.name(), outcome.name());
        }
    }

    public int failedAttempts(long approvalId, Instant since) {
        if (approvalId <= 0 || since == null) {
            return 0;
        }
        Integer count = jdbc.queryForObject(COUNT_SQL, Integer.class, approvalId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    private void attemptWriteFailure(String requestType) {
        try {
            meterRegistry.counter(ATTEMPT_WRITE_FAILURES, "request_type", requestType).increment();
        } catch (RuntimeException metricsFailure) {
            LOG.warn("ADMIN_METRIC_WRITE_FAILED meter={}", ATTEMPT_WRITE_FAILURES);
        }
    }

    /** Attempt categories collapse into the bounded metric outcome catalog. */
    private static String metricOutcome(Outcome outcome) {
        return switch (outcome) {
            case PROVIDER_FAILURE, AUDIT_FAILURE, STEP_UP_FAILURE -> "FAILURE";
            case VERSION_CONFLICT -> "CONFLICT";
            case TECHNICAL_FAILURE -> "ERROR";
        };
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null
                && requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
