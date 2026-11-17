package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.application.port.AuditRecordRef;
import com.simulatedbank.banking.audit.application.port.AuditRecordWriterPort;
import com.simulatedbank.banking.audit.domain.AuditActionCatalog;
import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.audit.domain.AuditEventValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Application service for recording immutable audit events inside caller transactions.
 * Orchestrates transaction propagation, event validation, C1/C2 criticality guard,
 * microsecond normalization, and rollback-only handling.
 * Interacts with persistence exclusively via AuditRecordWriterPort.
 */
@Service
public class AuditRecorder {
    private final AuditRecordWriterPort writerPort;

    public AuditRecorder(AuditRecordWriterPort writerPort) {
        this.writerPort = Objects.requireNonNull(writerPort, "writerPort is required");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditRecordRef recordInTransaction(AuditEvent event, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Audit C1/C2 requires caller transaction");
        }
        try {
            AuditEventValidator.validate(event);
            var criticality = AuditActionCatalog.require(event.action()).criticality();
            if (criticality != AuditActionCatalog.Criticality.C1 && criticality != AuditActionCatalog.Criticality.C2) {
                throw new IllegalArgumentException("Atomic audit writer accepts only C1/C2 actions");
            }
            if (occurredAt == null) throw new IllegalArgumentException("Event time is required");
            occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);

            return writerPort.append(event, occurredAt);
        } catch (RuntimeException exception) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw exception;
        }
    }
}
