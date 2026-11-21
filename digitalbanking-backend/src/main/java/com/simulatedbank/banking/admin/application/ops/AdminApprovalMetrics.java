package com.simulatedbank.banking.admin.application.ops;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BE-ADM-OPS-01: bounded Micrometer meters for the Admin Maker/Checker workflow.
 *
 * <p>BE-OBS-18 forbids unbounded label values. Every public method accepts only values from a fixed catalog and
 * silently ignores anything else, so a caller can never create a per-user/per-approval series. Metric recording
 * is telemetry only: a failure in Micrometer is caught and logged and can never change an approval decision.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public final class AdminApprovalMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApprovalMetrics.class);

    static final String PROPOSALS = "admin.approval.proposals";
    static final String DECISIONS = "admin.approval.decisions";
    static final String PROCESSING_DURATION = "admin.approval.processing.duration";
    static final String PROVIDER_EXECUTIONS = "admin.provider.executions";
    static final String PROVIDER_LATENCY = "admin.provider.latency";
    static final String VERSION_CONFLICTS = "admin.approval.version.conflicts";
    static final String RETRY_ATTEMPTS = "admin.approval.retry.attempts";
    static final String RETRY_EXHAUSTED = "admin.approval.retry.exhausted";
    static final String RECOVERY = "admin.approval.recovery";
    static final String STUCK = "admin.approval.stuck";

    private static final Set<String> METRIC_NAMES = Set.of(
            PROPOSALS, DECISIONS, PROCESSING_DURATION, PROVIDER_EXECUTIONS, PROVIDER_LATENCY,
            VERSION_CONFLICTS, RETRY_ATTEMPTS, RETRY_EXHAUSTED, RECOVERY, STUCK);

    private static final Set<String> REQUEST_TYPES = Set.of("LIMIT_CHANGE", "ROLE_CHANGE");
    private static final Set<String> DECISIONS_CATALOG = Set.of("APPROVE", "REJECT");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FAILURE", "REJECTED", "REPLAYED", "CONFLICT", "EXHAUSTED", "ERROR");
    private static final Set<String> PROVIDERS = Set.of("IDENTITY", "LIMIT", "AUDIT");
    private static final Set<String> OPERATIONS = Set.of(
            "APPLY_ROLE_CHANGE", "APPLY_LIMIT_CHANGE", "RECORD_AUDIT",
            "RECORD_IN_TRANSACTION", "RECORD_STANDALONE", "CONSUME_STEP_UP");

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> stuckGauges = new ConcurrentHashMap<>();

    public AdminApprovalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void proposal(String requestType, String outcome) {
        if (!REQUEST_TYPES.contains(requestType) || !OUTCOMES.contains(outcome)) {
            return;
        }
        counter(PROPOSALS, "request_type", requestType, "outcome", outcome);
    }

    public void decision(String requestType, String decision, String outcome, long nanos) {
        if (!REQUEST_TYPES.contains(requestType) || !DECISIONS_CATALOG.contains(decision)
                || !OUTCOMES.contains(outcome)) {
            return;
        }
        counter(DECISIONS, "request_type", requestType, "decision", decision, "outcome", outcome);
        timer(PROCESSING_DURATION, nanos, "request_type", requestType, "decision", decision, "outcome", outcome);
    }

    public void provider(String provider, String operation, String outcome, long nanos) {
        if (!PROVIDERS.contains(provider) || !OPERATIONS.contains(operation) || !OUTCOMES.contains(outcome)) {
            return;
        }
        counter(PROVIDER_EXECUTIONS, "provider", provider, "operation", operation, "outcome", outcome);
        timer(PROVIDER_LATENCY, nanos, "provider", provider, "operation", operation, "outcome", outcome);
    }

    public void versionConflict(String provider) {
        if (!PROVIDERS.contains(provider)) {
            return;
        }
        counter(VERSION_CONFLICTS, "provider", provider);
    }

    public void retryAttempt(String requestType, String outcome) {
        if (!REQUEST_TYPES.contains(requestType) || !OUTCOMES.contains(outcome)) {
            return;
        }
        counter(RETRY_ATTEMPTS, "request_type", requestType, "outcome", outcome);
    }

    public void retryExhausted(String requestType) {
        if (!REQUEST_TYPES.contains(requestType)) {
            return;
        }
        counter(RETRY_EXHAUSTED, "request_type", requestType);
    }

    public void recovery(String requestType, String outcome) {
        if (!REQUEST_TYPES.contains(requestType) || !OUTCOMES.contains(outcome)) {
            return;
        }
        counter(RECOVERY, "request_type", requestType, "outcome", outcome);
    }

    public void stuck(String requestType, long count) {
        if (!REQUEST_TYPES.contains(requestType)) {
            return;
        }
        long bounded = Math.max(0, count);
        try {
            AtomicLong gauge = stuckGauges.computeIfAbsent(requestType, type -> {
                AtomicLong value = new AtomicLong();
                AtomicLong registered = registry.gauge(STUCK, Tags.of("request_type", type), value);
                // Reuse the registry's gauge when another metrics instance registered it first.
                return registered == null ? value : registered;
            });
            gauge.set(bounded);
        } catch (RuntimeException metricsFailure) {
            LOG.warn("ADMIN_METRIC_WRITE_FAILED meter={}", STUCK);
        }
    }

    /** Base (dotted) meter names registered by this class; Prometheus rules derive the exported names. */
    public static Set<String> metricNames() {
        return METRIC_NAMES;
    }

    private void counter(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException metricsFailure) {
            LOG.warn("ADMIN_METRIC_WRITE_FAILED meter={}", name);
        }
    }

    private void timer(String name, long nanos, String... tags) {
        if (nanos < 0) {
            return;
        }
        try {
            registry.timer(name, tags).record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException metricsFailure) {
            LOG.warn("ADMIN_METRIC_WRITE_FAILED meter={}", name);
        }
    }
}
