package com.simulatedbank.banking.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BE-ADM-OPS-01: bounded label catalog and non-blocking failure policy of the approval metrics. */
class AdminApprovalMetricsTest {

    private static final Set<String> REQUEST_TYPES = Set.of("LIMIT_CHANGE", "ROLE_CHANGE");
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FAILURE", "REJECTED", "REPLAYED", "CONFLICT", "EXHAUSTED", "ERROR");
    private static final Set<String> PROVIDERS = Set.of("IDENTITY", "LIMIT", "AUDIT");
    private static final Set<String> OPERATIONS = Set.of(
            "APPLY_ROLE_CHANGE", "APPLY_LIMIT_CHANGE", "RECORD_AUDIT",
            "RECORD_IN_TRANSACTION", "RECORD_STANDALONE", "CONSUME_STEP_UP");

    private SimpleMeterRegistry registry;
    private AdminApprovalMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AdminApprovalMetrics(registry);
    }

    @Test
    @DisplayName("metricNames exposes exactly the documented workflow meters")
    void metricNamesExposeDocumentedMeters() {
        assertThat(AdminApprovalMetrics.metricNames()).containsExactlyInAnyOrder(
                "admin.approval.proposals",
                "admin.approval.decisions",
                "admin.approval.processing.duration",
                "admin.provider.executions",
                "admin.provider.latency",
                "admin.approval.version.conflicts",
                "admin.approval.retry.attempts",
                "admin.approval.retry.exhausted",
                "admin.approval.recovery",
                "admin.approval.stuck");
    }

    @Test
    @DisplayName("counters, timers and the stuck gauge record the documented values")
    void recordsCountersTimersAndGauge() {
        metrics.proposal("ROLE_CHANGE", "SUCCESS");
        metrics.decision("LIMIT_CHANGE", "APPROVE", "SUCCESS", 2_000_000L);
        metrics.provider("IDENTITY", "APPLY_ROLE_CHANGE", "SUCCESS", 1_000_000L);
        metrics.versionConflict("IDENTITY");
        metrics.retryAttempt("ROLE_CHANGE", "CONFLICT");
        metrics.retryExhausted("ROLE_CHANGE");
        metrics.recovery("ROLE_CHANGE", "ERROR");
        metrics.stuck("ROLE_CHANGE", 2);

        assertThat(registry.get("admin.approval.proposals")
                .tag("request_type", "ROLE_CHANGE").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("admin.approval.decisions")
                .tag("request_type", "LIMIT_CHANGE").tag("decision", "APPROVE")
                .tag("outcome", "SUCCESS").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.processing.duration")
                .tag("request_type", "LIMIT_CHANGE").tag("decision", "APPROVE")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("admin.provider.executions")
                .tag("provider", "IDENTITY").tag("operation", "APPLY_ROLE_CHANGE")
                .tag("outcome", "SUCCESS").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("admin.provider.latency")
                .tag("provider", "IDENTITY").tag("operation", "APPLY_ROLE_CHANGE")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("admin.approval.version.conflicts")
                .tag("provider", "IDENTITY").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.retry.attempts")
                .tag("request_type", "ROLE_CHANGE").tag("outcome", "CONFLICT")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.retry.exhausted")
                .tag("request_type", "ROLE_CHANGE").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("admin.approval.recovery")
                .tag("request_type", "ROLE_CHANGE").tag("outcome", "ERROR").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("admin.approval.stuck")
                .tag("request_type", "ROLE_CHANGE").gauge().value()).isEqualTo(2.0);

        metrics.stuck("ROLE_CHANGE", 0);
        assertThat(registry.get("admin.approval.stuck")
                .tag("request_type", "ROLE_CHANGE").gauge().value()).isZero();
    }

    @Test
    @DisplayName("every emitted tag value belongs to the fixed catalog")
    void everyEmittedTagValueIsBounded() {
        metrics.proposal("ROLE_CHANGE", "SUCCESS");
        metrics.proposal("LIMIT_CHANGE", "FAILURE");
        metrics.decision("ROLE_CHANGE", "APPROVE", "SUCCESS", 1);
        metrics.decision("LIMIT_CHANGE", "REJECT", "REJECTED", 1);
        metrics.provider("IDENTITY", "APPLY_ROLE_CHANGE", "SUCCESS", 1);
        metrics.provider("LIMIT", "APPLY_LIMIT_CHANGE", "FAILURE", 1);
        metrics.provider("AUDIT", "RECORD_AUDIT", "ERROR", 1);
        metrics.versionConflict("LIMIT");
        metrics.retryAttempt("ROLE_CHANGE", "FAILURE");
        metrics.retryExhausted("LIMIT_CHANGE");
        metrics.recovery("ROLE_CHANGE", "SUCCESS");
        metrics.stuck("LIMIT_CHANGE", 1);

        assertThat(registry.getMeters()).isNotEmpty();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                switch (tag.getKey()) {
                    case "request_type" -> assertThat(REQUEST_TYPES).contains(tag.getValue());
                    case "decision" -> assertThat(DECISIONS).contains(tag.getValue());
                    case "outcome" -> assertThat(OUTCOMES).contains(tag.getValue());
                    case "provider" -> assertThat(PROVIDERS).contains(tag.getValue());
                    case "operation" -> assertThat(OPERATIONS).contains(tag.getValue());
                    default -> throw new AssertionError("unexpected tag key " + tag.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("unexpected label values never create a series")
    void unexpectedValuesDoNotCreateMeters() {
        metrics.proposal("USER_CHANGE", "SUCCESS");
        metrics.proposal("ROLE_CHANGE", "BOGUS");
        metrics.decision("ROLE_CHANGE", "MAYBE", "SUCCESS", 1);
        metrics.decision("ROLE_CHANGE", "APPROVE", "BOGUS", 1);
        metrics.provider("EXTERNAL", "APPLY_ROLE_CHANGE", "SUCCESS", 1);
        metrics.provider("IDENTITY", "DROP TABLE", "SUCCESS", 1);
        metrics.versionConflict("EXTERNAL");
        metrics.retryAttempt("ROLE_CHANGE", "BOGUS");
        metrics.retryExhausted("RETENTION_HOLD");
        metrics.recovery("LIMIT_CHANGE", "WAT");
        metrics.stuck("UNKNOWN", 5);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("a broken MeterRegistry never propagates to the caller")
    void metricWriteFailuresAreContained() {
        AdminApprovalMetrics failing = new AdminApprovalMetrics(new FailingRegistry());

        assertThatCode(() -> {
            failing.proposal("ROLE_CHANGE", "SUCCESS");
            failing.decision("ROLE_CHANGE", "APPROVE", "SUCCESS", 1);
            failing.provider("IDENTITY", "APPLY_ROLE_CHANGE", "SUCCESS", 1);
            failing.versionConflict("AUDIT");
            failing.retryAttempt("ROLE_CHANGE", "AUDIT_FAILURE");
            failing.retryExhausted("ROLE_CHANGE");
            failing.recovery("ROLE_CHANGE", "SUCCESS");
            failing.stuck("ROLE_CHANGE", 1);
        }).doesNotThrowAnyException();
    }

    private static final class FailingRegistry extends SimpleMeterRegistry {
        @Override
        public Counter counter(String name, String... tags) {
            throw new IllegalStateException("metrics write rejected");
        }
    }
}
