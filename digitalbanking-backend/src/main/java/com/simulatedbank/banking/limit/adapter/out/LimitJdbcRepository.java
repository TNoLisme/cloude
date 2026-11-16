package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import java.math.BigDecimal;
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
        return insertConfiguration(config, false).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LimitConfiguration> insertProposal(LimitConfiguration config) {
        return insertConfiguration(config, true);
    }

    private Optional<LimitConfiguration> insertConfiguration(LimitConfiguration config, boolean proposal) {
        if (config.limitId() != null) {
            throw new IllegalArgumentException("New configuration must not have an ID");
        }
        LimitAmounts.requireValid(config.perTransactionLimit(), false);
        LimitAmounts.requireValid(config.dailyLimit(), false);
        return jdbc.query("""
                INSERT INTO banking.limit_config
                (customer_id, account_id, per_transaction_limit, daily_limit, currency,
                 effective_from, effective_to, status, version, created_by, approved_by, approval_id,
                 created_at, approved_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?, COALESCE(?, now()))
                """ + (proposal ? "ON CONFLICT (approval_id) WHERE approval_id IS NOT NULL DO NOTHING " : "")
                + "RETURNING *", this::configuration, config.customerId(), config.accountId(), config.perTransactionLimit(),
                config.dailyLimit(), config.currency(), timestamp(config.effectiveFrom()), timestamp(config.effectiveTo()),
                config.status() == null ? null : config.status().name(), config.version(), config.createdBy(),
                config.approvedBy(), config.approvalId(), timestamp(config.createdAt()), timestamp(config.approvedAt()),
                timestamp(config.updatedAt())).stream().findFirst();
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

    public boolean hasActivationAfter(long customerId, long sourceAccountId, Instant asOf) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM banking.limit_config
                  WHERE customer_id = ? AND (account_id IS NULL OR account_id = ?)
                    AND status = 'ACTIVE' AND effective_from > ? AND effective_from <= clock_timestamp()
                    AND (effective_to IS NULL OR effective_to > clock_timestamp())
                )
                """, Boolean.class, customerId, sourceAccountId, timestamp(asOf.truncatedTo(ChronoUnit.MICROS))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant transactionStart() {
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalArgumentException("Limit enforcement requires READ COMMITTED isolation");
        }
        return jdbc.queryForObject("SELECT transaction_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LimitUsage lockUsage(long customerId, Long accountId, LocalDate date) {
        if (accountId == null) {
            jdbc.update("""
                    INSERT INTO banking.limit_usage(customer_id, account_id, usage_date)
                    VALUES (?, NULL, ?)
                    ON CONFLICT (customer_id, usage_date) WHERE account_id IS NULL DO NOTHING
                    """, customerId, date);
        } else {
            jdbc.update("""
                    INSERT INTO banking.limit_usage(customer_id, account_id, usage_date)
                    VALUES (?, ?, ?)
                    ON CONFLICT (account_id, usage_date) WHERE account_id IS NOT NULL DO NOTHING
                    """, customerId, accountId, date);
        }
        var rows = jdbc.query("""
                SELECT * FROM banking.limit_usage
                WHERE customer_id = ? AND account_id IS NOT DISTINCT FROM ?::bigint AND usage_date = ?
                FOR UPDATE
                """, this::usage, customerId, accountId, date);
        if (rows.size() != 1) {
            throw new IllegalStateException("Usage scope does not match caller customer/account");
        }
        return rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementUsage(LimitUsage usage, BigDecimal amount, BigDecimal dailyLimit) {
        int updated = jdbc.update("""
                UPDATE banking.limit_usage
                SET used_amount = used_amount + ?, transfer_count = transfer_count + 1, updated_at = now()
                WHERE usage_id = ? AND customer_id = ? AND account_id IS NOT DISTINCT FROM ?::bigint
                  AND usage_date = ? AND (?::numeric IS NULL OR used_amount + ? <= ?)
                """, amount, usage.usageId(), usage.customerId(), usage.accountId(), usage.usageDate(),
                dailyLimit, amount, dailyLimit);
        if (updated != 1) {
            throw new IllegalStateException("Checked usage no longer permits completion");
        }
    }

    /** Shared by configuration changes and enforcement; always precedes usage row locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockChangeScope(long customerId, Long accountId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, row) -> true, "limit:customer:" + customerId);
        if (accountId != null) {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (rs, row) -> true, "limit:account:" + accountId);
        }
    }

    public Instant currentTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    public List<LimitConfiguration> findScope(long customerId, Long accountId) {
        return jdbc.query("""
                SELECT * FROM banking.limit_config
                WHERE account_id IS NOT DISTINCT FROM ?::bigint
                  AND (account_id IS NOT NULL OR customer_id = ?)
                ORDER BY version
                """, this::configuration, accountId, customerId);
    }

    public Optional<LimitConfiguration> findProposal(long approvalId, boolean forUpdate) {
        return jdbc.query("SELECT * FROM banking.limit_config WHERE approval_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                this::configuration, approvalId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void supersede(long limitId, Instant now) {
        int updated = jdbc.update("""
                UPDATE banking.limit_config SET status='SUPERSEDED',
                  effective_to=LEAST(COALESCE(effective_to, ?), ?), updated_at=?
                WHERE limit_id=? AND status='ACTIVE'
                """, timestamp(now), timestamp(now), timestamp(now), limitId);
        if (updated != 1) throw new IllegalStateException("Active configuration changed during decision");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LimitConfiguration decideProposal(long limitId, boolean approve, long checkerId, Instant now) {
        return jdbc.queryForObject("""
                UPDATE banking.limit_config SET status=?,
                  effective_from=CASE WHEN ? THEN ? ELSE effective_from END,
                  effective_to=CASE WHEN ? THEN NULL ELSE effective_to END,
                  approved_by=?, approved_at=?, updated_at=?
                WHERE limit_id=? AND status='PENDING_APPROVAL'
                RETURNING *
                """, this::configuration, approve ? "ACTIVE" : "REJECTED", approve, timestamp(now), approve,
                approve ? checkerId : null, approve ? timestamp(now) : null, timestamp(now), limitId);
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
