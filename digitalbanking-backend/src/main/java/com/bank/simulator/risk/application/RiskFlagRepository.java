package com.bank.simulator.risk.application;

import com.bank.simulator.shared.pagination.CursorPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RiskFlagRepository {
    private final JdbcTemplate jdbc;

    public RiskFlagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID transferId, String ruleId, String ruleVersion, String reason, Instant detectedAt) {
        jdbc.update("""
                INSERT INTO risk_flags (id, transfer_id, rule_id, rule_version, reason, detected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (transfer_id, rule_id, rule_version) DO NOTHING
                """, UUID.randomUUID(), transferId, ruleId, ruleVersion, reason, Timestamp.from(detectedAt));
    }

    public List<RiskFlagView> find(RiskFlagQuery query, CursorPosition cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, transfer_id, rule_id, rule_version, reason, detected_at
                FROM risk_flags
                WHERE (CAST(? AS VARCHAR) IS NULL OR rule_id = ?)
                  AND (CAST(? AS UUID) IS NULL OR transfer_id = ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR detected_at >= ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR detected_at < ?)
                  AND (CAST(? AS TIMESTAMPTZ) IS NULL OR detected_at < ? OR (detected_at = ? AND id < ?))
                ORDER BY detected_at DESC, id DESC
                LIMIT ?
                """);
        Timestamp cursorTime = cursor == null ? null : Timestamp.from(cursor.createdAt());
        return jdbc.query(sql.toString(), (rs, row) -> new RiskFlagView(
                        rs.getObject("id", UUID.class), rs.getObject("transfer_id", UUID.class),
                        rs.getString("rule_id"), rs.getString("rule_version"), rs.getString("reason"),
                        rs.getTimestamp("detected_at").toInstant()),
                query.ruleId(), query.ruleId(), query.transferId(), query.transferId(),
                query.from() == null ? null : Timestamp.from(query.from()), query.from() == null ? null : Timestamp.from(query.from()),
                query.to() == null ? null : Timestamp.from(query.to()), query.to() == null ? null : Timestamp.from(query.to()),
                cursorTime, cursorTime, cursorTime, cursor == null ? null : cursor.id(), limit);
    }
}
