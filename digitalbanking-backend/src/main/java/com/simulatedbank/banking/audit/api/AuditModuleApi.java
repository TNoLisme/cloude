package com.simulatedbank.banking.audit.api;

import java.util.Optional;

/**
 * Provider-owned audit contract consumed by every module that must record business evidence
 * (design/01 §4.3, docs-common/design/05 §9).
 *
 * <p>Only the write side is opened here: the Admin module needs durable evidence for approval decisions and
 * security denials. Query/export/retention endpoints belong to the Audit module's own REST surface and are not
 * part of this v1 port.</p>
 */
public interface AuditModuleApi {

    /**
     * C1/C2: appends the event inside the caller transaction ({@code Propagation.MANDATORY}, audit phase last).
     * A validation or persistence failure raises {@code BankingBusinessException} and rolls the business
     * transaction back — callers must never report success when this call fails.
     *
     * @return the persisted record reference (id + hash of this record)
     */
    AuditRecordRef recordInTransaction(AuditEvent event);

    /**
     * C3: appends the event in its own transaction ({@code Propagation.REQUIRES_NEW}). A failure never changes
     * the (already decided) denial response and must stay visible through the audit failure metric/log.
     *
     * @return the persisted record reference, or empty when the write failed or the event is not C3
     */
    Optional<AuditRecordRef> recordStandalone(AuditEvent event);
}
