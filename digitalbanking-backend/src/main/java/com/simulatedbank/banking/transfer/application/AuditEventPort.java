package com.simulatedbank.banking.transfer.application;

import java.util.Optional;

/**
 * TRFC-AU-02 seam: best-effort standalone (REQUIRES_NEW) C3 audit owned by the Audit provider.
 * A successful empty result means the record was not persisted; a system failure must throw so the
 * caller can fall back to a security log/metric. Never used to replace C1/C2 mandatory audit.
 */
@FunctionalInterface
public interface AuditEventPort {
    Optional<Long> recordStandalone(AuditEvent event);
}