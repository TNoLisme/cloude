package com.bank.simulator.audit.api;

import java.util.UUID;

public interface AuditWriter {
    void record(UUID actorId, String actorRole, String eventType, String targetType, UUID targetId,
                String outcome, UUID correlationId, String summary);
}
