package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditClass;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.audit.domain.ValidatedAuditEvent;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable audit provider behind {@link AuditModuleApi} (design/01 §4.3/§5.2/§7).
 *
 * <p><b>Chain integrity.</b> Every record is appended to one global SHA-256 chain. The writer takes the
 * transaction-scoped advisory lock, reads the previous {@code record_hash}, and inserts a row whose hash covers
 * the JCS canonical payload plus that previous hash. Because the lock is held until commit, concurrent writers
 * serialise and the persisted chain remains unbroken; a rollback leaves only an {@code audit_id} gap, which is
 * not tampering because verification follows {@code prev_hash}.</p>
 *
 * <p><b>Lock scope.</b> The C2 path joins the caller transaction ({@code MANDATORY}) and must be the last
 * database phase before commit: after the advisory lock no business SQL, ORM flush or network call is allowed
 * (design/01 §7.5).</p>
 *
 * <p><b>Failure behaviour.</b> C1/C2 validation or persistence failures raise
 * {@link BankingBusinessException} with {@link ErrorCode#AUDIT_WRITE_FAILED} and roll the business transaction
 * back — callers must never report success. C3 {@link #recordStandalone(AuditEvent)} runs in
 * {@code REQUIRES_NEW}; it returns {@link Optional#empty()} for failures and for non-C3 actions, never throws to
 * the caller, and leaves evidence through the bounded {@code audit.write.failures} counter and a
 * {@code SECURITY_AUDIT_WRITE_FAILED} log line without payload, actor or entity identifiers.</p>
 */
@Service
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditModuleService implements AuditModuleApi {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.audit.AUDIT");

    private static final String WRITTEN_METRIC = "audit.written";
    private static final String FAILURE_METRIC = "audit.write.failures";
    private static final String UNKNOWN_LABEL = "UNKNOWN";

    private final AuditEventValidator validator;
    private final AuditChainWriter writer;
    private final MeterRegistry meters;
    private final TransactionTemplate standalone;

    public AuditModuleService(AuditEventValidator validator, AuditChainWriter writer, MeterRegistry meters,
            PlatformTransactionManager transactionManager) {
        this.validator = validator;
        this.writer = writer;
        this.meters = meters;
        this.standalone = new TransactionTemplate(transactionManager);
        this.standalone.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditRecordRef recordInTransaction(AuditEvent event) {
        try {
            ValidatedAuditEvent validated = validator.validate(event);
            AuditRecordRef reference = writer.append(validated);
            count(WRITTEN_METRIC, event.action());
            return reference;
        } catch (RuntimeException failure) {
            count(FAILURE_METRIC, actionOf(event));
            logFailure(event, failure);
            if (failure instanceof BankingBusinessException rejection) {
                throw rejection;
            }
            throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED, "Audit write failed",
                    java.util.Map.of(), failure);
        }
    }

    @Override
    public Optional<AuditRecordRef> recordStandalone(AuditEvent event) {
        if (event == null || event.action() == null) {
            count(FAILURE_METRIC, null);
            logFailure(null, null);
            return Optional.empty();
        }
        if (event.action().auditClass() != AuditClass.C3) {
            // Criticality is a property of the action: a C1/C2 record may never be downgraded to best effort.
            return Optional.empty();
        }
        try {
            ValidatedAuditEvent validated = validator.validate(event);
            // Catch begin, append and commit failures after the standalone transaction has ended.
            AuditRecordRef reference = standalone.execute(status -> writer.append(validated));
            var result = Optional.of(reference);
            count(WRITTEN_METRIC, event.action());
            return result;
        } catch (RuntimeException failure) {
            count(FAILURE_METRIC, event.action());
            logFailure(event, failure);
            return Optional.empty();
        }
    }

    private void count(String name, AuditAction action) {
        String auditClass = action == null ? UNKNOWN_LABEL : action.auditClass().name();
        String actionLabel = action == null ? UNKNOWN_LABEL : action.name();
        try {
            meters.counter(name, "class", auditClass, "action", actionLabel).increment();
        } catch (RuntimeException metricsFailure) {
            // A metrics outage must never change the audit contract (C2 stays fail-closed, C3 stays best-effort).
            LOG.warn("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }

    private static void logFailure(AuditEvent event, RuntimeException failure) {
        AuditAction action = actionOf(event);
        LOG.error("SECURITY_AUDIT_WRITE_FAILED action={} audit_class={} entity_type={} correlation_id={} error={}",
                action == null ? UNKNOWN_LABEL : action.name(),
                action == null ? UNKNOWN_LABEL : action.auditClass().name(),
                event == null ? UNKNOWN_LABEL : event.entityType(),
                event == null ? UNKNOWN_LABEL : event.correlationId(),
                failure == null ? UNKNOWN_LABEL : failure.getClass().getSimpleName());
    }

    private static AuditAction actionOf(AuditEvent event) {
        return event == null ? null : event.action();
    }
}
