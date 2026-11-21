package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.logging.LogRedaction;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Durable Audit for Transfer OTP lifecycle, with security-log telemetry and C3 fallback. */
@Component
public class SecurityLogAuditAdapter implements AuthAuditPort {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT");

    private final MeterRegistry meterRegistry;

    private final ObjectProvider<AuditModuleApi> audit;
    private final Clock clock;

    @Autowired
    public SecurityLogAuditAdapter(MeterRegistry meterRegistry, ObjectProvider<AuditModuleApi> audit, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.audit = audit;
        this.clock = clock;
    }

    public SecurityLogAuditAdapter(MeterRegistry meterRegistry) {
        this(meterRegistry, null, Clock.systemUTC());
    }

    @Override
    public void recordInTransaction(AuthAuditEvent event, String correlationId) {
        if (isTransferChallenge(event) || event.action() == AuthAction.AUTH_CHALLENGE_RESENT) {
            auditProvider().recordInTransaction(challengeEvent(event, correlationId));
        }
        emit(event, correlationId, "STANDALONE_TX");
    }

    @Override
    public void recordStandalone(AuthAuditEvent event, String correlationId) {
        try {
            if (isTransferChallenge(event) && auditProvider().recordStandalone(challengeEvent(event, correlationId)).isEmpty()) {
                throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED);
            }
            emit(event, correlationId, "BEST_EFFORT");
        } catch (RuntimeException failure) {
            // design/01 §5.2: C3 fail không được ảnh hưởng response; security log + metric + alert thay thế.
            counter("auth.audit.failures", "action", event.action().name(),
                    "class", event.auditClass().name());
            LOG.error("SECURITY_AUDIT_WRITE_FAILED action={} correlationId={}", event.action(), correlationId);
        }
    }

    private static boolean isTransferChallenge(AuthAuditEvent event) {
        return event.challenge() != null && "TRANSFER_STEP_UP".equals(event.challenge().purpose());
    }

    private AuditModuleApi auditProvider() {
        AuditModuleApi provider = audit == null ? null : audit.getIfAvailable();
        if (provider == null) {
            throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED);
        }
        return provider;
    }

    private AuditEvent challengeEvent(AuthAuditEvent event, String correlationId) {
        var challenge = event.challenge();
        boolean locked = event.action() == AuthAction.AUTH_CHALLENGE_LOCKED;
        boolean success = event.auditClass() == AuditClass.C2;
        Map<String, Object> state = success
                ? Map.of("purpose", challenge.purpose(), "method", challenge.method(),
                        "status", challenge.status(), "attemptCount", challenge.attempts())
                : locked ? Map.of("status", challenge.status(), "attemptCount", challenge.attempts())
                        : Map.of("reasonCode", event.reasonCode());
        return new AuditEvent(AuditAction.valueOf(event.action().name()),
                locked ? AuditActorType.SYSTEM : AuditActorType.USER, locked ? null : event.actorUserId(),
                locked ? "SYSTEM" : challengeRole(challenge.purpose()), "AUTH_CHALLENGE", challenge.ref(), null, state,
                null, null,
                success ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, correlationId,
                RequestContextHolder.current().map(RequestContext::requestId).orElse(null), null, clock.instant());
    }

    private static String challengeRole(String purpose) {
        return switch (purpose) {
            case "LOGIN_NEW_DEVICE" -> "PRE_AUTH";
            case "TRANSFER_STEP_UP" -> "CUSTOMER";
            default -> "STAFF";
        };
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException metricsFailure) {
            // Metric thất bại không được che mất fallback security log.
            LOG.error("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }

    /**
     * Điểm write duy nhất (metric + security log). `protected` để test double mô phỏng
     * persist thật của module audit (design/01 §6.1) khi thay thế adapter này —
     * hợp đồng: exception C2 lan truyền, C3 được nuốt ở recordStandalone.
     */
    protected void emit(AuthAuditEvent event, String correlationId, String mode) {
        counter("auth.audit.events", "action", event.action().name(),
                "class", event.auditClass().name(), "mode", mode, "outcome", "SUCCESS");
        // C3 fail-closed-only fields; không message nội dung nhạy cảm; detail qua redaction trung tâm.
        LOG.info("SECURITY_AUDIT action={} outcome={} actor_user_id={} anonymous_ref={} entity_id={} "
                        + "reason_code={} detail={} correlation_id={}",
                event.action(), event.auditClass(), event.actorUserId(), event.anonymousRef(),
                event.entityId(), event.reasonCode(), LogRedaction.redactText(event.detail()), correlationId);
    }
}
