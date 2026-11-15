package com.simulatedbank.banking.audit.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Bounded full-chain scan for the current audit hash v1 proposal. */
public final class AuditChainJdbcVerifier {
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final int batchSize;

    public AuditChainJdbcVerifier(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_BATCH_SIZE);
    }

    public AuditChainJdbcVerifier(JdbcTemplate jdbc, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        }
        this.jdbc = jdbc;
        this.batchSize = batchSize;
    }

    public AuditChainVerifier.Result verify() {
        String previous = com.simulatedbank.banking.audit.domain.AuditHashing.GENESIS_HASH;
        Long upper = jdbc.queryForObject("SELECT max(audit_id) FROM banking.audit_log", Long.class);
        if (upper == null) return new AuditChainVerifier.Result(AuditChainVerifier.Status.EMPTY, null);
        long lastId = 0;
        while (true) {
            List<Row> rows = jdbc.query("SELECT audit_id, hash_version, prev_hash, record_hash, canonical_payload::text AS payload, "
                            + "jsonb_build_object('occurred_at', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), "
                            + "'actor_type', actor_type, 'actor_user_id', CASE WHEN actor_user_id IS NULL THEN NULL ELSE actor_user_id::text END, "
                            + "'actor_role', actor_role, 'action', action, 'entity_type', entity_type, 'entity_id', entity_id, "
                            + "'before_state', before_state, 'after_state', after_state, 'amount', CASE WHEN amount IS NULL THEN NULL ELSE to_char(amount, 'FM9999999999999999990.00') END, "
                            + "'currency', currency, 'correlation_id', correlation_id, 'request_id', request_id, 'outcome', outcome, "
                            + "'client_info_masked', client_info_masked, 'hash_version', hash_version)::text AS columns "
                            + "FROM banking.audit_log WHERE audit_id > ? AND audit_id <= ? ORDER BY audit_id ASC LIMIT ?",
                    (rs, index) -> map(rs), lastId, upper, batchSize);
            if (rows.isEmpty()) return new AuditChainVerifier.Result(lastId == upper ? AuditChainVerifier.Status.PASS : AuditChainVerifier.Status.INCOMPLETE, lastId == upper ? null : "UPPER_BOUND_MISSING");
            for (Row row : rows) {
                if (row.auditId() <= lastId) return new AuditChainVerifier.Result(AuditChainVerifier.Status.MISMATCH, "ORDER");
                JsonNode columns;
                try { columns = JSON.readTree(row.columns()); }
                catch (Exception ex) { return new AuditChainVerifier.Result(AuditChainVerifier.Status.MISMATCH, "COLUMNS"); }
                var result = AuditChainVerifier.verifyRow(row.payload(), row.version(), row.previousHash(), row.recordHash(), previous, columns);
                if (result.status() != AuditChainVerifier.Status.PASS) return result;
                previous = row.recordHash();
                lastId = row.auditId();
            }
        }
    }

    private record Row(long auditId, int version, String previousHash, String recordHash, String payload, String columns) { }
    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("audit_id"), rs.getInt("hash_version"), rs.getString("prev_hash"),
                rs.getString("record_hash"), rs.getString("payload"), rs.getString("columns"));
    }
}
