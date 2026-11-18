package com.simulatedbank.banking.audit.api;

import java.util.Optional;

/** Provider-owned audit write contract shared by business modules. */
public interface AuditModuleApi {
    /** JOIN/C1-C2: a failed append rolls back the caller's transaction. */
    AuditRecordRef recordInTransaction(AuditEvent event);

    /** Independent C3: best-effort append with an observable fallback on failure. */
    Optional<AuditRecordRef> recordStandalone(AuditEvent event);
}
