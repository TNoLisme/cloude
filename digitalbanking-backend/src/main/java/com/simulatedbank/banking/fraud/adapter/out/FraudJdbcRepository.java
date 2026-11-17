package com.simulatedbank.banking.fraud.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudFlagEntity;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudFlagJpaRepository;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudFlagMapper;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudRuleConfigId;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudRuleConfigJpaRepository;
import com.simulatedbank.banking.fraud.adapter.out.persistence.FraudRuleConfigMapper;
import com.simulatedbank.banking.fraud.domain.*;
import com.simulatedbank.banking.fraud.application.port.FraudPersistencePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
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
 * Persistence adapter for fraud_rule_config and fraud_flag tables in schema banking.
 * Reference configuration data and standard flag lookups utilize Spring Data JPA repositories
 * and MapStruct mappers, while concurrency-critical operations (pessimistic lock NOWAIT and
 * atomic terminal state verification) are retained in native SQL / JdbcTemplate with explicit technical justification.
 */
@Repository
public class FraudJdbcRepository implements FraudPersistencePort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final FraudRuleConfigJpaRepository ruleConfigJpaRepository;
    private final FraudRuleConfigMapper ruleConfigMapper;
    private final FraudFlagJpaRepository flagJpaRepository;
    private final FraudFlagMapper flagMapper;
    private final FraudMatchedValueValidator matchedValueValidator = new FraudMatchedValueValidator();

    @Autowired
    public FraudJdbcRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            FraudRuleConfigJpaRepository ruleConfigJpaRepository,
            FraudRuleConfigMapper ruleConfigMapper,
            FraudFlagJpaRepository flagJpaRepository,
            FraudFlagMapper flagMapper
    ) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.ruleConfigJpaRepository = java.util.Objects.requireNonNull(ruleConfigJpaRepository, "ruleConfigJpaRepository is required");
        this.ruleConfigMapper = java.util.Objects.requireNonNull(ruleConfigMapper, "ruleConfigMapper is required");
        this.flagJpaRepository = java.util.Objects.requireNonNull(flagJpaRepository, "flagJpaRepository is required");
        this.flagMapper = java.util.Objects.requireNonNull(flagMapper, "flagMapper is required");
    }

    /**
     * Test-only fallback constructor for isolated DB unit tests executing without Spring application context.
     */
    public FraudJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.objectMapper = new ObjectMapper();
        this.ruleConfigJpaRepository = null;
        this.ruleConfigMapper = null;
        this.flagJpaRepository = null;
        this.flagMapper = null;
    }

    public void insertRuleConfig(FraudRuleConfig config) {
        if (ruleConfigJpaRepository == null || ruleConfigMapper == null) {
            throw new IllegalStateException("FraudRuleConfigJpaRepository and FraudRuleConfigMapper are required for insertRuleConfig");
        }
        ruleConfigJpaRepository.saveAndFlush(ruleConfigMapper.toEntity(config));
    }

    public List<FraudRuleConfig> findActiveRuleConfigs(Instant evaluationTime) {
        if (ruleConfigJpaRepository == null || ruleConfigMapper == null) {
            throw new IllegalStateException("FraudRuleConfigJpaRepository and FraudRuleConfigMapper are required for findActiveRuleConfigs");
        }
        return ruleConfigJpaRepository.findActiveRules(evaluationTime).stream()
                .map(ruleConfigMapper::toDomain)
                .toList();
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
        if (flagJpaRepository == null) {
            throw new IllegalStateException("FraudFlagJpaRepository is required for insertFlag");
        }
        FraudFlagEntity entity = FraudFlagEntity.builder()
                .transferId(transferId)
                .ruleCode(ruleCode)
                .ruleVersion(ruleVersion)
                .severity(severity)
                .status(FlagStatus.OPEN)
                .matchedValue(matchedValue)
                .correlationId(correlationId)
                .build();
        FraudFlagEntity saved = flagJpaRepository.save(entity);
        return saved.getFlagId() != null ? saved.getFlagId() : 0L;
    }

    public Optional<FraudFlagItem> findFlagById(long flagId) {
        if (flagJpaRepository == null || flagMapper == null) {
            throw new IllegalStateException("FraudFlagJpaRepository and FraudFlagMapper are required for findFlagById");
        }
        return flagJpaRepository.findById(flagId).map(flagMapper::toDomain);
    }

    public List<FraudFlagItem> findFlagsByTransferId(long transferId) {
        if (flagJpaRepository == null || flagMapper == null) {
            throw new IllegalStateException("FraudFlagJpaRepository and FraudFlagMapper are required for findFlagsByTransferId");
        }
        return flagJpaRepository.findByTransferId(transferId).stream()
                .map(flagMapper::toDomain)
                .toList();
    }

    public Optional<FraudFlagItem> findFlagForUpdateNowait(long flagId) {
        String sql = """
                SELECT flag_id, transfer_id, rule_code, rule_version, severity, status, matched_value::text,
                       reviewed_by, reviewed_at, decision, notes, correlation_id, created_at
                FROM banking.fraud_flag
                WHERE flag_id = ?
                FOR UPDATE NOWAIT
                """;

        try {
            List<FraudFlagItem> results = jdbc.query(sql, new FraudFlagRowMapper(), flagId);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (DataAccessException dae) {
            if (isLockNotAvailable(dae)) {
                throw new BankingBusinessException(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED,
                        "Fraud flag review conflict: row is currently locked by another concurrent review (flagId=" + flagId + ")",
                        Map.of(), dae);
            }
            throw dae;
        }
    }

    private boolean isLockNotAvailable(DataAccessException dae) {
        if (dae instanceof PessimisticLockingFailureException || dae instanceof CannotAcquireLockException) {
            return true;
        }
        Throwable cause = dae;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx) {
                if ("55P03".equals(sqlEx.getSQLState())) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
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
