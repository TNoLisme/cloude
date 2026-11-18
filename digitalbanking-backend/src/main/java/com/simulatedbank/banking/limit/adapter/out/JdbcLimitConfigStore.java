package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.application.LimitConfigStore;
import com.simulatedbank.banking.limit.domain.LimitConfig;
import com.simulatedbank.banking.limit.domain.LimitConfigStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@code banking.limit_config} (V9, 04 §4.5.1).
 *
 * <p>Native SQL is required for row locks ({@code SELECT ... FOR UPDATE}) and transaction
 * advisory locks. Only {@link LimitConfig} aggregates cross the package boundary; no JPA
 * entity or raw row map is exposed. All methods join the caller's transaction: the Limit
 * mutations are {@code Propagation.MANDATORY} because they must commit/rollback together with
 * the Admin approval and its C2 audit.</p>
 */
@Component
@ConditionalOnProperty(name = {"app.limit.enabled"}, havingValue = "true", matchIfMissing = true)
class JdbcLimitConfigStore implements LimitConfigStore {

    private static final String COLUMNS = """
            limit_id, customer_id, account_id, per_transaction_limit, daily_limit, currency,
            effective_from, status, version, created_by, approved_by, approval_id
            """;

    private static final String SELECT_ACTIVE_CUSTOMER = "SELECT " + COLUMNS
            + " FROM banking.limit_config"
            + " WHERE customer_id = ? AND account_id IS NULL AND status = 'ACTIVE'";

    private static final String SELECT_ACTIVE_ACCOUNT = "SELECT " + COLUMNS
            + " FROM banking.limit_config"
            + " WHERE customer_id = ? AND account_id = ? AND status = 'ACTIVE'";

    private static final String SELECT_BY_APPROVAL = "SELECT " + COLUMNS
            + " FROM banking.limit_config WHERE approval_id = ?";

    private static final String INSERT_PENDING = """
            INSERT INTO banking.limit_config
                (customer_id, account_id, per_transaction_limit, daily_limit, currency, effective_from,
                 status, version, created_by, approval_id)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?)
            RETURNING limit_id
            """;

    private static final String SUPERSEDE = """
            UPDATE banking.limit_config
               SET status = 'SUPERSEDED', updated_at = ?
             WHERE limit_id = ? AND status = 'ACTIVE'
            """;

    private static final String ACTIVATE = """
            UPDATE banking.limit_config
               SET status = 'ACTIVE', approved_by = ?, approved_at = ?, updated_at = ?
             WHERE limit_id = ? AND status = 'PENDING_APPROVAL'
            """;

    private static final String REJECT = """
            UPDATE banking.limit_config
               SET status = 'REJECTED', updated_at = ?
             WHERE limit_id = ? AND status = 'PENDING_APPROVAL'
            """;

    /**
     * Namespace for the transaction advisory lock; keeps Limit keys apart from any future
     * advisory-lock user in the same database.
     */
    private static final long ADVISORY_NAMESPACE = 0x4C494D49_54000000L;

    private final JdbcTemplate jdbc;

    JdbcLimitConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LimitConfig> findActive(long customerId, Long accountId) {
        return queryOne(accountId == null ? SELECT_ACTIVE_CUSTOMER : SELECT_ACTIVE_ACCOUNT,
                accountId == null ? new Object[] {customerId} : new Object[] {customerId, accountId});
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LimitConfig> findByApprovalId(long approvalId) {
        return queryOne(SELECT_BY_APPROVAL, new Object[] {approvalId});
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LimitConfig> lockActive(long customerId, Long accountId) {
        return queryOne((accountId == null ? SELECT_ACTIVE_CUSTOMER : SELECT_ACTIVE_ACCOUNT) + " FOR UPDATE",
                accountId == null ? new Object[] {customerId} : new Object[] {customerId, accountId});
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LimitConfig> lockByApprovalId(long approvalId) {
        return queryOne(SELECT_BY_APPROVAL + " FOR UPDATE", new Object[] {approvalId});
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockScope(long customerId, Long accountId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null, advisoryKey(customerId, accountId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertPending(LimitConfig pending) {
        Long id = jdbc.queryForObject(INSERT_PENDING, Long.class,
                pending.customerId(), pending.accountId(), pending.perTransactionLimit(), pending.dailyLimit(),
                pending.currency(), timestamp(pending.effectiveFrom()), pending.version(), pending.createdBy(),
                pending.approvalId());
        if (id == null || id <= 0) {
            throw new IllegalStateException("Inserted limit configuration has no identifier");
        }
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean supersede(long limitConfigId, Instant updatedAt) {
        return jdbc.update(SUPERSEDE, timestamp(updatedAt), limitConfigId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean activate(long limitConfigId, long approvedBy, Instant approvedAt) {
        return jdbc.update(ACTIVATE, approvedBy, timestamp(approvedAt), timestamp(approvedAt), limitConfigId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reject(long limitConfigId, Instant updatedAt) {
        return jdbc.update(REJECT, timestamp(updatedAt), limitConfigId) == 1;
    }

    private Optional<LimitConfig> queryOne(String sql, Object[] arguments) {
        List<LimitConfig> rows = jdbc.query(sql, this::map, arguments);
        if (rows.size() > 1) {
            // Both partial unique indexes make this impossible; never guess which row wins.
            throw new IllegalStateException("Multiple matching limit configurations");
        }
        return rows.stream().findFirst();
    }

    private LimitConfig map(ResultSet rs, int rowNum) throws SQLException {
        return LimitConfig.restore(
                rs.getLong("limit_id"),
                rs.getLong("customer_id"),
                rs.getObject("account_id", Long.class),
                rs.getBigDecimal("per_transaction_limit"),
                rs.getBigDecimal("daily_limit"),
                rs.getString("currency"),
                instant(rs, "effective_from"),
                LimitConfigStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getLong("created_by"),
                rs.getObject("approved_by", Long.class),
                rs.getObject("approval_id", Long.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /**
     * Stable 64-bit key for the scope. A collision between two scopes only serializes them
     * extra (no correctness impact); the namespace keeps Limit locks distinguishable.
     */
    private static long advisoryKey(long customerId, Long accountId) {
        long scope = accountId == null ? 0x9E3779B97F4A7C15L : accountId;
        long mixed = customerId * 0x9E3779B97F4A7C15L + scope;
        mixed ^= mixed >>> 33;
        mixed *= 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 33;
        return ADVISORY_NAMESPACE ^ mixed;
    }
}
