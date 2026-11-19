package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore.Details;
import com.simulatedbank.banking.transfer.application.AuditEvent;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Flag;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.application.AuditEventPort;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public final class TransferAuditAdapter implements AuditEventPort {
    private final AuditModuleApi audit;

    public TransferAuditAdapter(AuditModuleApi audit) {
        this.audit = audit;
    }

    @Override
    public Optional<Long> recordStandalone(AuditEvent event) {
        String entityId = event.entityId() == null ? "UNKNOWN" : event.entityId();
        return audit.recordStandalone(new com.simulatedbank.banking.audit.api.AuditEvent(
                AuditAction.valueOf(event.action()), AuditActorType.USER, event.actorUserId(),
                event.actorRole() == null ? "UNKNOWN" : event.actorRole(), event.entityType(), entityId, null,
                Map.of("reasonCode", event.afterState().get("reasonCode")), null, null, AuditOutcome.REJECTED,
                event.correlationId(), event.requestId(), null, event.occurredAt())).map(AuditRecordRef::auditId);
    }

    void recordCreated(TransferIdentity identity, TransferAmount amount, String correlationId, Instant createdAt) {
        audit.recordInTransaction(new com.simulatedbank.banking.audit.api.AuditEvent(
                AuditAction.TRANSFER_CREATED, AuditActorType.USER, identity.initiatedByUserId(), "CUSTOMER",
                "TRANSFER", identity.reference(), null, Map.of("status", "PENDING"), amount.value(), amount.currency(),
                AuditOutcome.SUCCESS, correlationId, RequestContextHolder.current().map(RequestContext::requestId).orElse(null),
                null, createdAt));
    }

    void recordFraudFlag(Details details, Flag flag, Instant terminalAt) {
        audit.recordInTransaction(new com.simulatedbank.banking.audit.api.AuditEvent(
                AuditAction.FRAUD_FLAG_CREATED, AuditActorType.SYSTEM, null, "SYSTEM", "FRAUD_FLAG",
                Long.toString(flag.id()), null, Map.of("transferReference", details.reference(),
                        "ruleCode", flag.ruleCode(), "ruleVersion", flag.ruleVersion(),
                        "severity", flag.severity(), "status", "OPEN"),
                null, null, AuditOutcome.SUCCESS, details.intent().correlationId(),
                RequestContextHolder.current().map(RequestContext::requestId).orElse(null), null, terminalAt));
    }

    void recordTerminal(Details details, Outcome outcome, Instant terminalAt) {
        var failure = outcome.failure();
        boolean failed = failure.isPresent();
        var after = failure.<Map<String, Object>>map(code -> Map.of("status", "FAILED", "failureCode", code.name()))
                .orElseGet(() -> Map.of("status", "COMPLETED"));
        var intent = details.intent();
        audit.recordInTransaction(new com.simulatedbank.banking.audit.api.AuditEvent(
                failed ? AuditAction.TRANSFER_FAILED : AuditAction.TRANSFER_COMPLETED,
                AuditActorType.USER, details.userId(), "CUSTOMER", "TRANSFER", details.reference(),
                Map.of("status", "PENDING"), after,
                intent.amount(), intent.currency(), failed ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS,
                intent.correlationId(), RequestContextHolder.current().map(RequestContext::requestId).orElse(null),
                null, terminalAt));
    }
}
