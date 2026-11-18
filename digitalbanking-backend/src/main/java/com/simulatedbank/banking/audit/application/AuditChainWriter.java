package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.audit.domain.ValidatedAuditEvent;

/**
 * Outbound port of the audit write protocol (design/01 §7.5). Implementations append exactly one record to the
 * global hash chain inside the caller transaction and never deduplicate: idempotency is a consumer contract.
 */
public interface AuditChainWriter {

    /**
     * Appends the record under the chain advisory lock and returns its durable reference.
     *
     * <p>The method must run inside an existing transaction ({@code MANDATORY}); the advisory lock is held until
     * that transaction ends, so no business SQL may follow the append.</p>
     */
    AuditRecordRef append(ValidatedAuditEvent event);
}
