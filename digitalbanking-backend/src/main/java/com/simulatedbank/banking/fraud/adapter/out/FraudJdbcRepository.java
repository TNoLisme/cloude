package com.simulatedbank.banking.fraud.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.fraud.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for fraud_rule_config and fraud_flag tables in schema banking.
 */
@Repository
public class FraudJdbcRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final FraudMatchedValueValidator matchedValueValidator = new FraudMatchedValueValidator();

    @Autowired
    public FraudJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public FraudJdbcRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public void insertRuleConfig(FraudRuleConfig config) {
        String sql = """
                INSERT INTO banking.fraud_rule_config
                (rule_code, version, rule_name, rule_type, threshold_amount, threshold_count, window_seconds,
                 severity, enabled, effective_from, effective_to, created_by, approved_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        Timestamp effectiveTo = config.effectiveTo() != null ? Timestamp.from(config.effectiveTo()) : null;
        Timestamp createdAt = config.createdAt() != null ? Timestamp.from(config.createdAt()) : Timestamp.from(Instant.now());
        Timestamp updatedAt = config.updatedAt() != null ? Timestamp.from(config.updatedAt()) : Timestamp.from(Instant.now());

        jdbc.update(
                sql,
                config.ruleCode(),
                config.version(),
                config.ruleName(),
                config.ruleType().name(),
                config.thresholdAmount(),
                config.thresholdCount(),
                config.windowSeconds(),
                config.severity().name(),
                config.enabled(),
                Timestamp.from(config.effectiveFrom()),
                effectiveTo,
                config.createdBy(),
                config.approvedBy(),
                createdAt,
                updatedAt
        );
    }

    public List<FraudRuleConfig> findActiveRuleConfigs(Instant evaluationTime) {
        String sql = """
                SELECT rule_code, version, rule_name, rule_type, threshold_amount, threshold_count, window_seconds,
                       severity, enabled, effective_from, effective_to, created_by, approved_by, created_at, updated_at
                FROM banking.fraud_rule_config
                WHERE enabled = TRUE
                  AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                ORDER BY rule_code, version DESC
                """;

        Timestamp evalTs = Timestamp.from(evaluationTime);
        return jdbc.query(sql, new FraudRuleConfigRowMapper(), evalTs, evalTs);
    }

    public long insertFlag(
            long transferId,
            String ruleCode,
            int ruleVersion,
            Severity severity,
            Map<String, Object> matchedValue,
            String correlationId
    ) {
        matchedValueValidator.validate(matchedValue);
        String json = serializeMatchedValue(matchedValue);
        String sql = """
                INSERT INTO banking.fraud_flag
                (transfer_id, rule_code, rule_version, severity, status, matched_value, correlation_id)
                VALUES (?, ?, ?, ?, 'OPEN', ?::jsonb, ?)
                RETURNING flag_id
                """;

        Long flagId = jdbc.queryForObject(
                sql,
                Long.class,
                transferId,
                ruleCode,
                ruleVersion,
                severity.name(),
                json,
                correlationId
        );

        return flagId != null ? flagId : 0L;
    }

    public Optional<FraudFlagItem> findFlagById(long flagId) {
        String sql = """
                SELECT flag_id, transfer_id, rule_code, rule_version, severity, status, matched_value::text,
                       reviewed_by, reviewed_at, decision, notes, correlation_id, created_at
                FROM banking.fraud_flag
                WHERE flag_id = ?
                """;

        List<FraudFlagItem> results = jdbc.query(sql, new FraudFlagRowMapper(), flagId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<FraudFlagItem> findFlagForUpdateNowait(long flagId) {
        String sql = """
                SELECT flag_id, transfer_id, rule_code, rule_version, severity, status, matched_value::text,
                       reviewed_by, reviewed_at, decision, notes, correlation_id, created_at
                FROM banking.fraud_flag
                WHERE flag_id = ?
                FOR UPDATE NOWAIT
                """;

        List<FraudFlagItem> results = jdbc.query(sql, new FraudFlagRowMapper(), flagId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean updateReview(
            long flagId,
            FlagStatus targetStatus,
            ReviewDecision decision,
            String notes,
            Long reviewedBy,
            Instant reviewedAt
    ) {
        String sql = """
                UPDATE banking.fraud_flag
                SET status = ?,
                    decision = ?,
                    notes = ?,
                    reviewed_by = ?,
                    reviewed_at = ?
                WHERE flag_id = ?
                  AND status IN ('OPEN', 'UNDER_REVIEW')
                """;

        int affected = jdbc.update(
                sql,
                targetStatus.name(),
                decision.name(),
                notes,
                reviewedBy,
                Timestamp.from(reviewedAt),
                flagId
        );
        return affected > 0;
    }

    private String serializeMatchedValue(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Collections.emptyMap() : map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize matchedValue", e);
        }
    }

    private class FraudRuleConfigRowMapper implements RowMapper<FraudRuleConfig> {
        @Override
        public FraudRuleConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp effTo = rs.getTimestamp("effective_to");
            return new FraudRuleConfig(
                    rs.getString("rule_code"),
                    rs.getInt("version"),
                    rs.getString("rule_name"),
                    RuleType.valueOf(rs.getString("rule_type")),
                    rs.getBigDecimal("threshold_amount"),
                    rs.getObject("threshold_count", Integer.class),
                    rs.getObject("window_seconds", Integer.class),
                    Severity.valueOf(rs.getString("severity")),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("effective_from").toInstant(),
                    effTo != null ? effTo.toInstant() : null,
                    rs.getLong("created_by"),
                    rs.getObject("approved_by", Long.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    }

    private class FraudFlagRowMapper implements RowMapper<FraudFlagItem> {
        @Override
        public FraudFlagItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            String json = rs.getString("matched_value");
            Map<String, Object> matchedValue;
            try {
                matchedValue = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                matchedValue = Collections.emptyMap();
            }

            Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
            String decisionStr = rs.getString("decision");

            return new FraudFlagItem(
                    rs.getLong("flag_id"),
                    rs.getLong("transfer_id"),
                    rs.getString("rule_code"),
                    rs.getInt("rule_version"),
                    Severity.valueOf(rs.getString("severity")),
                    FlagStatus.valueOf(rs.getString("status")),
                    matchedValue,
                    rs.getObject("reviewed_by", Long.class),
                    reviewedAt != null ? reviewedAt.toInstant() : null,
                    decisionStr != null ? ReviewDecision.valueOf(decisionStr) : null,
                    rs.getString("notes"),
                    rs.getString("correlation_id"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    }
}
