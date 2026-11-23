package com.simulatedbank.banking.fraud.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.ApiPage;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.application.port.FraudFlagQuery;
import com.simulatedbank.banking.fraud.domain.FraudMatchedValueValidator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "app.fraud.enabled", havingValue = "true", matchIfMissing = true)
public class FraudFlagHttpQuery implements FraudFlagQuery {
    private static final String TABLES = """
            FROM banking.fraud_flag f
            JOIN banking.transfer_fraud_review_context t ON t.transfer_id = f.transfer_id
            JOIN banking.fraud_rule_config r ON r.rule_code = f.rule_code AND r.version = f.rule_version
            """;
    private static final String SELECT = """
            SELECT f.flag_id, t.reference_no, t.customer_id, f.rule_code, f.rule_version, r.rule_type, f.severity, f.status,
                   f.matched_value::text, f.decision, f.reviewed_by, f.reviewed_at, f.correlation_id, f.created_at
            """ + TABLES;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FraudMatchedValueValidator validator = new FraudMatchedValueValidator();

    public FraudFlagHttpQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiPage<Flag> search(ApiPage.Window window, String sort, String status, String severity,
            String ruleCode, String reference, Long customerId) {
        var params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(window.from())).addValue("to", Timestamp.from(window.to()))
                .addValue("limit", window.size()).addValue("offset", window.offset());
        var conditions = new StringBuilder(" WHERE f.created_at >= :from AND f.created_at < :to");
        add(conditions, params, "f.status", "status", status);
        add(conditions, params, "f.severity", "severity", severity);
        add(conditions, params, "f.rule_code", "ruleCode", ruleCode);
        add(conditions, params, "t.reference_no", "reference", reference);
        add(conditions, params, "t.customer_id", "customerId", customerId);
        String severityOrder = "CASE f.severity WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 ELSE 4 END";
        String order = switch (sort) {
            case "createdAt,asc" -> "f.created_at ASC, f.flag_id ASC";
            case "createdAt,desc" -> "f.created_at DESC, f.flag_id DESC";
            case "severity,asc" -> severityOrder + " ASC, f.flag_id ASC";
            case "severity,desc" -> severityOrder + " DESC, f.flag_id DESC";
            default -> throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        };
        long total = jdbc.queryForObject("SELECT count(*) " + TABLES + conditions, params, Long.class);
        var flags = jdbc.query(SELECT + conditions + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                params, this::map);
        return ApiPage.of(flags, total, window);
    }

    @Override
    public Flag get(long flagId) {
        return jdbc.query(SELECT + " WHERE f.flag_id = :flag", Map.of("flag", flagId), this::map)
                .stream().findFirst().orElseThrow(() -> new BankingBusinessException(ErrorCode.FRAUD_FLAG_NOT_FOUND));
    }

    private static void add(StringBuilder conditions, MapSqlParameterSource params, String column,
            String parameter, Object value) {
        if (value != null) {
            conditions.append(" AND ").append(column).append(" = :").append(parameter);
            params.addValue(parameter, value);
        }
    }

    private Flag map(ResultSet row, int index) throws SQLException {
        var reviewTime = row.getTimestamp("reviewed_at");
        String reviewerId = row.getString("reviewed_by");
        String rawMatchedValue = row.getString("matched_value");
        Map<String, Object> matchedValue = null;
        if (rawMatchedValue != null) {
            try {
                matchedValue = json.readValue(rawMatchedValue, new TypeReference<>() {});
                validator.validate(matchedValue);
            } catch (JsonProcessingException | IllegalArgumentException invalid) {
                throw new IllegalStateException("Invalid fraud flag projection");
            }
        }
        return new Flag(row.getString("flag_id"), row.getString("reference_no"), row.getString("customer_id"),
                new Rule(row.getString("rule_code"), row.getInt("rule_version"), row.getString("rule_type")), row.getString("severity"),
                row.getString("status"), matchedValue, row.getString("decision"),
                reviewerId == null ? null : new Reviewer(reviewerId, "Nhân viên " + reviewerId),
                reviewTime == null ? null : reviewTime.toInstant(), row.getString("correlation_id"),
                row.getTimestamp("created_at").toInstant());
    }
}
