package com.simulatedbank.banking.admin.application.ops;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BE-ADM-OPS-01: structured workflow facts for the Admin Maker/Checker path.
 *
 * <p>Logs are operational evidence, never the audit record. Only the fixed key catalog is emitted
 * ({@code stage}, {@code request_type}, {@code decision}, {@code outcome}, {@code error_code},
 * {@code approval_id}, {@code attempts}, {@code age_bucket}, {@code correlation_id}, {@code request_id});
 * free text, payloads, notes, tokens and exception messages never reach this logger.
 * {@code SafeJsonLogEncoder} enforces the same allowlist at the stdout boundary.</p>
 *
 * <p>Allowed stage values are uppercase catalog names (for example {@code PROPOSAL}, {@code DECISION},
 * {@code PROVIDER}, {@code RECOVERY_SCAN}, {@code RECOVERY_SWEEP}, {@code STUCK_APPROVAL},
 * {@code RETRY_EXHAUSTED}). Unknown or unbounded values are dropped instead of logged. Logging failures are
 * contained so a telemetry problem can never change an approval response.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public final class AdminWorkflowLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.admin.WORKFLOW");
    private static final String EVENT = "ADMIN_APPROVAL_WORKFLOW";

    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FAILURE", "REJECTED", "REPLAYED", "CONFLICT", "EXHAUSTED", "ERROR",
            "PROVIDER_FAILURE", "VERSION_CONFLICT", "AUDIT_FAILURE", "STEP_UP_FAILURE", "TECHNICAL_FAILURE");
    private static final Set<String> AGE_BUCKETS = Set.of(
            "LT_5M", "5M_30M", "30M_1H", "1H_6H", "6H_24H", "GT_24H");

    public void transition(String stage, ApprovalRequestType type, String decision, String outcome,
            String errorCode, String approvalId, String correlationId, String requestId) {
        try {
            var builder = LOG.atInfo();
            builder = add(builder, "stage", catalogName(stage));
            builder = add(builder, "request_type", requestType(type));
            builder = add(builder, "decision", member(DECISIONS, decision));
            builder = add(builder, "outcome", member(OUTCOMES, outcome));
            builder = add(builder, "error_code", errorCode(errorCode));
            builder = add(builder, "approval_id", approvalId(approvalId));
            builder = add(builder, "correlation_id", correlation(correlationId));
            builder = add(builder, "request_id", requestId(requestId));
            builder.log(EVENT);
        } catch (RuntimeException loggingFailure) {
            // Telemetry only: never turn a workflow fact into a business failure.
        }
    }

    public void recovery(String stage, ApprovalRequestType type, String outcome, int attempts, String ageBucket,
            String correlationId) {
        try {
            var builder = LOG.atInfo();
            builder = add(builder, "stage", catalogName(stage));
            builder = add(builder, "request_type", requestType(type));
            builder = add(builder, "outcome", member(OUTCOMES, outcome));
            builder = add(builder, "attempts", boundedAttempts(attempts));
            builder = add(builder, "age_bucket", member(AGE_BUCKETS, ageBucket));
            builder = add(builder, "correlation_id", correlation(correlationId));
            builder.log(EVENT);
        } catch (RuntimeException loggingFailure) {
            // Telemetry only.
        }
    }

    private static org.slf4j.spi.LoggingEventBuilder add(
            org.slf4j.spi.LoggingEventBuilder builder, String key, String value) {
        return value == null ? builder : builder.addKeyValue(key, value);
    }

    private static String catalogName(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,31}") ? value : null;
    }

    private static String requestType(ApprovalRequestType type) {
        return type != null && type.openInMvp() ? type.name() : null;
    }

    private static String member(Set<String> allowed, String value) {
        return value != null && allowed.contains(value) ? value : null;
    }

    private static String errorCode(String value) {
        if (value == null) {
            return null;
        }
        try {
            ErrorCode.valueOf(value);
            return value;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String approvalId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}") ? value : null;
    }

    private static String correlation(String value) {
        return RequestContext.isValidCorrelationId(value) ? value : null;
    }

    private static String requestId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ? value : null;
    }

    private static String boundedAttempts(int attempts) {
        return attempts >= 0 && attempts <= 1_000_000 ? Integer.toString(attempts) : null;
    }
}
