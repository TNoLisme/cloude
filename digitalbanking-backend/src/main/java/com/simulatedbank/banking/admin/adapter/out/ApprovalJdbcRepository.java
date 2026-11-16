package com.simulatedbank.banking.admin.adapter.out;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApprovalJdbcRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ApprovalJdbcRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest create(String targetType, String targetId, Map<String, Object> payload,
            long makerId, String correlationId) {
        return jdbc.queryForObject("""
                INSERT INTO banking.approval_request(request_type,target_type,target_id,payload,maker_id,correlation_id)
                VALUES ('LIMIT_CHANGE',?,?,?::jsonb,?,?) RETURNING *
                """, this::read, targetType, targetId, encode(payload), makerId, correlationId);
    }

    public Optional<ApprovalRequest> find(long id) {
        return jdbc.query("SELECT * FROM banking.approval_request WHERE approval_id=? AND request_type='LIMIT_CHANGE'",
                this::read, id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest lock(long id) {
        return jdbc.queryForObject("SELECT * FROM banking.approval_request WHERE approval_id=? AND request_type='LIMIT_CHANGE' FOR UPDATE",
                this::read, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest setPayload(long id, Map<String, Object> payload) {
        return jdbc.queryForObject("UPDATE banking.approval_request SET payload=?::jsonb WHERE approval_id=? AND status='PENDING' RETURNING *",
                this::read, encode(payload), id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest decide(long id, boolean approve, long checker, String note) {
        return jdbc.queryForObject("""
                UPDATE banking.approval_request SET status=?, checker_id=?, decision_note=?,
                  decided_at=clock_timestamp(), applied_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END
                WHERE approval_id=? AND request_type='LIMIT_CHANGE' AND status='PENDING' RETURNING *
                """, this::read, approve ? "APPLIED" : "REJECTED", checker, note, approve, id);
    }

    private String encode(Map<String, Object> payload) {
        try { return json.writeValueAsString(payload); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid approval payload"); }
    }

    private ApprovalRequest read(ResultSet rs, int row) throws SQLException {
        try {
            return new ApprovalRequest(rs.getLong("approval_id"), rs.getString("request_type"), rs.getString("target_type"),
                    rs.getString("target_id"), json.readValue(rs.getString("payload"), new TypeReference<>() { }),
                    rs.getString("status"), rs.getLong("maker_id"), rs.getObject("checker_id", Long.class), rs.getString("decision_note"),
                    rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                    rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toInstant());
        } catch (java.io.IOException invalid) { throw new SQLException("Invalid stored approval payload", invalid); }
    }
}
