package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService;
import com.simulatedbank.banking.admin.application.ops.AdminWorkflowLog;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * BE-ADM-OPS-01: scheduled read-only detection sweep for stuck/exhausted Admin approvals.
 *
 * <p>Same scheduling convention as {@link AdminIdempotencyPurgeJob}. The sweep only calls
 * {@link AdminApprovalRecoveryService#scan()}; it never mutates an approval or provider state. A failure is
 * bounded telemetry: the {@code admin.approval.recovery{outcome="ERROR"}} counter plus a fixed log line, without
 * SQL text, exception messages or identifiers. The next scheduled run retries naturally.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = {"app.admin.enabled", "app.admin.ops.enabled"},
        havingValue = "true", matchIfMissing = true)
public class AdminApprovalRecoveryJob {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.admin.RECOVERY");
    private static final ApprovalRequestType[] METERED_TYPES = {
            ApprovalRequestType.LIMIT_CHANGE, ApprovalRequestType.ROLE_CHANGE
    };

    private final AdminApprovalRecoveryService recovery;
    private final AdminApprovalMetrics metrics;
    private final AdminWorkflowLog workflow;

    public AdminApprovalRecoveryJob(AdminApprovalRecoveryService recovery, AdminApprovalMetrics metrics,
            AdminWorkflowLog workflow) {
        this.recovery = recovery;
        this.metrics = metrics;
        this.workflow = workflow;
    }

    @Scheduled(fixedDelayString = "${app.admin.ops.sweep-delay:PT5M}",
            initialDelayString = "${app.admin.ops.sweep-initial-delay:PT1M}")
    public void sweep() {
        try {
            recovery.scan();
        } catch (RuntimeException sweepFailure) {
            for (ApprovalRequestType type : METERED_TYPES) {
                metrics.recovery(type.name(), "ERROR");
            }
            workflow.transition("RECOVERY_SWEEP", null, null, "ERROR", null, null, null, null);
            LOG.error("ADMIN_RECOVERY_SWEEP_FAILED");
        }
    }
}
