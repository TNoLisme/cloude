package com.bank.simulator.audit.application;

import com.bank.simulator.audit.api.AuditWriter;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AppendAuditFactService {
    private final AuditWriter writer;

    public AppendAuditFactService(AuditWriter writer) {
        this.writer = writer;
    }

    public void append(AuditFact fact) {
        writer.record(fact.actorId(), fact.actorRole(), fact.eventType(), fact.targetType(), fact.targetId(),
                fact.outcome(), fact.correlationId(), fact.summary());
    }

    public record AuditFact(UUID actorId, String actorRole, String eventType, String targetType,
                            UUID targetId, String outcome, UUID correlationId, String summary) {}
}
