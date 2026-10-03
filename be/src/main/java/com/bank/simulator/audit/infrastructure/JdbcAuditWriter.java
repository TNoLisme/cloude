package com.bank.simulator.audit.infrastructure;

import com.bank.simulator.audit.api.AuditWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcAuditWriter implements AuditWriter {

    private final JdbcTemplate jdbc;

    public JdbcAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorId, String actorRole, String eventType, String targetType, UUID targetId,
                       String outcome, UUID correlationId, String summary) {
        jdbc.update("""
                INSERT INTO audit_events (id, actor_id, actor_role, event_type, target_type, target_id,
                                          outcome, correlation_id, summary, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                """, UUID.randomUUID(), actorId, actorRole, eventType, targetType, targetId,
                outcome, correlationId == null ? UUID.randomUUID() : correlationId, summary);
    }
}
