package com.bank.simulator.audit.application;

import com.bank.simulator.shared.pagination.CursorPosition;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(UUID eventId, String eventType, UUID actorId, String targetType,
                             UUID targetId, String outcome, Instant occurredAt,
                             UUID correlationId, String summary) {
    static AuditEventView from(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditEventView(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getObject("actor_id", UUID.class), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), rs.getString("outcome"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getObject("correlation_id", UUID.class),
                rs.getString("summary"));
    }

    CursorPosition cursorPosition() {
        return new CursorPosition(1, occurredAt, eventId);
    }
}
