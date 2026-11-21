package com.simulatedbank.banking.fraud.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.fraud.api.FraudEvaluation;
import com.simulatedbank.banking.fraud.domain.FraudRuleConfig;
import com.simulatedbank.banking.fraud.domain.FraudRuleEvaluator;
import com.simulatedbank.banking.fraud.domain.FraudTransferSnapshot;
import com.simulatedbank.banking.fraud.domain.RuleType;
import com.simulatedbank.banking.fraud.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** JDBC-only work so rolling back Fraud never invalidates managed JPA state in T2. */
@Repository
public class JdbcFraudEvaluation implements FraudEvaluation {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcFraudEvaluation.class);
    private static final String EFFECTIVE_RULES = """
            SELECT rule_code, version, rule_name, rule_type, threshold_amount, threshold_count, window_seconds,
                   severity, enabled, effective_from, effective_to, created_by, approved_by, created_at,
                   updated_at, auto_block
            FROM banking.fraud_rule_config
            WHERE enabled AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
            ORDER BY rule_code, version
            """;
    private final JdbcTemplate jdbc;
    private final FraudRuleEvaluator evaluator;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final long budgetNanos;

    public JdbcFraudEvaluation(JdbcTemplate jdbc, FraudRuleEvaluator evaluator, ObjectMapper json,
                               MeterRegistry meters, AppProperties properties) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.json = json;
        this.meters = meters;
        long budgetMs = properties.transfer().fraudEvalTimeoutMs();
        if (budgetMs <= 0) throw new IllegalArgumentException("Fraud evaluation budget must be positive");
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    @Override
    public void verifyReady() {
        if (!Integer.valueOf(2).equals(jdbc.queryForObject("""
                SELECT count(DISTINCT rule_code) FROM banking.fraud_rule_config
                WHERE rule_code IN ('LARGE_VALUE_TRANSFER', 'HIGH_FREQUENCY_TRANSFER')
                """, Integer.class))) {
            throw new IllegalStateException("Missing baseline Fraud configuration");
        }
        jdbc.queryForList("SELECT customer_id, created_at FROM banking.fraud_transfer_intent_history LIMIT 0");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision evaluateTransfer(Facts facts) {
        var status = TransactionAspectSupport.currentTransactionStatus();
        return jdbc.execute((ConnectionCallback<Decision>) connection -> {
            if (!DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), jdbc.getDataSource())
                    || connection.getAutoCommit() || connection.isReadOnly() || status.isRollbackOnly()) {
                throw new IllegalStateException("Fraud requires a usable writable T2 connection");
            }

            var savepoint = connection.setSavepoint();
            Decision decision;
            try {
                decision = evaluateAndInsert(facts, System.nanoTime());
            } catch (RuntimeException failure) {
                var category = recoverableCategory(failure);
                if (category == null) throw failure;
                connection.rollback(savepoint);
                if (status.isRollbackOnly() || !Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) {
                    throw new IllegalStateException("T2 unusable after Fraud rollback", failure);
                }
                connection.releaseSavepoint(savepoint);
                observeFailure(category, facts.correlationId());
                return new Decision(Outcome.ENGINE_ERROR, List.of());
            }
            connection.releaseSavepoint(savepoint);
            return decision;
        });
    }

    private Decision evaluateAndInsert(Facts facts, long started) {
        OffsetDateTime end = facts.terminalAt().atOffset(ZoneOffset.UTC);
        var rules = jdbc.query(EFFECTIVE_RULES, (rs, row) -> new Rule(new FraudRuleConfig(
                rs.getString("rule_code"), rs.getInt("version"), rs.getString("rule_name"),
                RuleType.valueOf(rs.getString("rule_type")), rs.getBigDecimal("threshold_amount"),
                rs.getObject("threshold_count", Integer.class), rs.getObject("window_seconds", Integer.class),
                Severity.valueOf(rs.getString("severity")), rs.getBoolean("enabled"),
                instant(rs.getObject("effective_from", OffsetDateTime.class)),
                instant(rs.getObject("effective_to", OffsetDateTime.class)),
                rs.getLong("created_by"), rs.getObject("approved_by", Long.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class))), rs.getBoolean("auto_block")), end, end);
        checkBudget(started);
        var codes = new HashSet<String>();
        for (Rule rule : rules) {
            if (!codes.add(rule.config().ruleCode())) {
                throw new IllegalStateException("Multiple effective versions of the same Fraud rule");
            }
        }

        var snapshot = new FraudTransferSnapshot(facts.transferId(), facts.customerId(), facts.sourceId(),
                facts.destinationId(), facts.amount(), facts.currency(), facts.terminalAt(), 0, false, facts.correlationId());
        var counts = new HashMap<Integer, Integer>();
        var flags = new ArrayList<Flag>();
        boolean blocked = false;
        for (Rule rule : rules) {
            var match = evaluator.evaluateRule(snapshot, rule.config(),
                    seconds -> counts.computeIfAbsent(seconds, window -> countIntents(facts.customerId(), end, window)));
            checkBudget(started);
            if (match.isEmpty()) continue;
            var matched = match.get();
            Long flagId = jdbc.queryForObject("""
                    INSERT INTO banking.fraud_flag (transfer_id, rule_code, rule_version, severity, status,
                                                    matched_value, correlation_id, created_at)
                    VALUES (?, ?, ?, ?, 'OPEN', CAST(? AS jsonb), ?, ?)
                    RETURNING flag_id
                    """, Long.class, facts.transferId(), matched.ruleCode(), matched.ruleVersion(),
                    matched.severity().name(), encode(matched.matchedValue()), facts.correlationId(), end);
            if (flagId == null || flagId <= 0) throw new IllegalStateException("Missing inserted Fraud flag ID");
            flags.add(new Flag(flagId, matched.ruleCode(), matched.ruleVersion(), matched.severity().name()));
            blocked |= rule.autoBlock();
            checkBudget(started);
        }
        return new Decision(blocked ? Outcome.BLOCK : Outcome.ALLOW, flags);
    }

    private void checkBudget(long started) {
        if (System.nanoTime() - started >= budgetNanos) throw new EvaluationBudgetExceeded();
    }

    private int countIntents(long customerId, OffsetDateTime end, int windowSeconds) {
        return Math.toIntExact(jdbc.queryForObject("""
                SELECT count(*) FROM banking.fraud_transfer_intent_history
                WHERE customer_id = ? AND created_at > ? AND created_at <= ?
                """, Long.class, customerId, end.minusSeconds(windowSeconds), end));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new EvidenceEncodingException(failure);
        }
    }

    private static ErrorCategory recoverableCategory(RuntimeException failure) {
        if (failure instanceof EvaluationBudgetExceeded) return ErrorCategory.EVALUATION_BUDGET;
        if (failure instanceof EvidenceEncodingException) return ErrorCategory.EVIDENCE_ENCODING;
        // P0001 raised within the Fraud block denotes an engine execution error. Integrity,
        // contention, cancellation and connection failures stay in the caller's T2 error path.
        if (failure instanceof DataAccessException database
                && database.getMostSpecificCause() instanceof SQLException sql
                && "P0001".equals(sql.getSQLState())) return ErrorCategory.ENGINE_SQL;
        return null;
    }

    private void observeFailure(ErrorCategory category, String correlationId) {
        try {
            LOG.atWarn().addKeyValue("category", category.name())
                    .addKeyValue("fallback", "ALLOW").addKeyValue("correlation_id", correlationId)
                    .log("FRAUD_ENGINE_ERROR");
        } catch (RuntimeException ignored) {
            // A telemetry problem must not change the recovered T2 result.
        }
        try {
            meters.counter("banking.fraud.engine.error").increment();
        } catch (RuntimeException ignored) {
            // Keep logging and metrics independent.
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private enum ErrorCategory { ENGINE_SQL, EVIDENCE_ENCODING, EVALUATION_BUDGET }

    private static final class EvaluationBudgetExceeded extends RuntimeException { }

    private static final class EvidenceEncodingException extends RuntimeException {
        EvidenceEncodingException(JsonProcessingException cause) {
            super("Unable to encode allowlisted Fraud evidence", cause);
        }
    }

    private record Rule(FraudRuleConfig config, boolean autoBlock) { }
}
