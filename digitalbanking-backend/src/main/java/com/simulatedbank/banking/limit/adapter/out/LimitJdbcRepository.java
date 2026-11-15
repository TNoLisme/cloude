package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LimitJdbcRepository {
    private final JdbcTemplate jdbc;

    public LimitJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LimitConfiguration insertConfiguration(LimitConfiguration config) {
        if (config.limitId() != null) {
            throw new IllegalArgumentException("New configuration must not have an ID");
        }
        LimitAmounts.requireValid(config.perTransactionLimit(), false);
        LimitAmounts.requireValid(config.dailyLimit(), false);
        return jdbc.queryForObject("""
                INSERT INTO banking.limit_config
                (customer_id, account_id, per_transaction_limit, daily_limit, currency,
                 effective_from, effective_to, status, version, created_by, approved_by, approval_id,
                 created_at, approved_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?, COALESCE(?, now()))
                RETURNING *
                """, this::configuration, config.customerId(), config.accountId(), config.perTransactionLimit(),
                config.dailyLimit(), config.currency(), timestamp(config.effectiveFrom()), timestamp(config.effectiveTo()),
                config.status() == null ? null : config.status().name(), config.version(), config.createdBy(),
                config.approvedBy(), config.approvalId(), timestamp(config.createdAt()), timestamp(config.approvedAt()),
                timestamp(config.updatedAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LimitUsage insertUsage(LimitUsage usage) {
        if (usage.usageId() != null) {
            throw new IllegalArgumentException("New usage must not have an ID");
        }
        LimitAmounts.requireValid(usage.usedAmount(), true);
        return jdbc.queryForObject("""
                INSERT INTO banking.limit_usage
                (customer_id, account_id, usage_date, currency, used_amount, transfer_count, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, now())) RETURNING *
                """, this::usage, usage.customerId(), usage.accountId(), usage.usageDate(), usage.currency(),
                usage.usedAmount(), usage.transferCount(), timestamp(usage.updatedAt()));
    }

    public Optional<LimitConfiguration> findConfiguration(long limitId) {
        return jdbc.query("SELECT * FROM banking.limit_config WHERE limit_id = ?", this::configuration, limitId)
                .stream().findFirst();
    }

    public Optional<LimitUsage> findUsage(long customerId, Long accountId, LocalDate date) {
        return jdbc.query("""
                SELECT * FROM banking.limit_usage
                WHERE customer_id = ? AND account_id IS NOT DISTINCT FROM ?::bigint AND usage_date = ?
                """, this::usage, customerId, accountId, date).stream().findFirst();
    }

    public List<LimitConfiguration> findEffective(long customerId, long sourceAccountId, Instant asOf) {
        // DB boundaries have microsecond precision; flooring preserves < / <= at submicrosecond instants.
        Timestamp queryTime = timestamp(asOf.truncatedTo(ChronoUnit.MICROS));
        return jdbc.query("""
                SELECT * FROM banking.limit_config
                WHERE customer_id = ? AND (account_id IS NULL OR account_id = ?)
                  AND status = 'ACTIVE' AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                ORDER BY account_id NULLS FIRST
                """, this::configuration, customerId, sourceAccountId, queryTime, queryTime);
    }

    private LimitConfiguration configuration(ResultSet rs, int row) throws SQLException {
        return new LimitConfiguration(rs.getLong("limit_id"), rs.getLong("customer_id"),
                rs.getObject("account_id", Long.class), rs.getBigDecimal("per_transaction_limit"),
                rs.getBigDecimal("daily_limit"), rs.getString("currency"), instant(rs, "effective_from"),
                instant(rs, "effective_to"), LimitConfiguration.Status.valueOf(rs.getString("status")),
                rs.getInt("version"), rs.getLong("created_by"), rs.getObject("approved_by", Long.class),
                rs.getObject("approval_id", Long.class), instant(rs, "created_at"), instant(rs, "approved_at"),
                instant(rs, "updated_at"));
    }

    private LimitUsage usage(ResultSet rs, int row) throws SQLException {
        return new LimitUsage(rs.getLong("usage_id"), rs.getLong("customer_id"), rs.getObject("account_id", Long.class),
                rs.getObject("usage_date", LocalDate.class), rs.getString("currency"), rs.getBigDecimal("used_amount"),
                rs.getInt("transfer_count"), instant(rs, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
