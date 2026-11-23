package com.simulatedbank.banking.audit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Internal recovery read of terminal C1 records by immutable Transfer reference. */
public interface TransferAuditEvidence {
    List<Completion> findCompletions(String reference);

    record Completion(long id, String action, String outcome, String status, String actorType, Long actorId,
                      BigDecimal amount, String currency, String correlationId, Instant occurredAt) { }
}
