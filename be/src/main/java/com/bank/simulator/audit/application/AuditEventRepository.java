package com.bank.simulator.audit.application;

import com.bank.simulator.shared.pagination.CursorPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditEventRepository {
    private final JdbcTemplate jdbc;

    public AuditEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEventView> find(AuditQuery query, CursorPosition cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, actor_id, target_type, target_id, outcome,
                       occurred_at, correlation_id, summary
                FROM audit_events
                WHERE (CAST(? AS VARCHAR) IS NULL OR event_type = ?)
                  AND (CAST(? AS UUID) IS NULL OR actor_id = ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR occurred_at >= ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR occurred_at < ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR occurred_at < ? OR (occurred_at = ? AND id < ?))
                ORDER BY occurred_at DESC, id DESC
                LIMIT ?
                """);
        Object cursorTime = cursor == null ? null : Timestamp.from(cursor.createdAt());
        return jdbc.query(sql.toString(), (rs, row) -> AuditEventView.from(rs),
                query.eventType(), query.eventType(), query.actorId(), query.actorId(),
                query.from() == null ? null : Timestamp.from(query.from()), query.from() == null ? null : Timestamp.from(query.from()),
                query.to() == null ? null : Timestamp.from(query.to()), query.to() == null ? null : Timestamp.from(query.to()),
                cursorTime, cursorTime, cursorTime, cursor == null ? null : cursor.id(), limit);
    }
}
