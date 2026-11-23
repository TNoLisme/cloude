package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.application.port.AuditQueryPort;
import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only bounded JDBC projections for audit search and detail.
 * Implements AuditQueryPort.
 * <p>
 * Stack Convention Rationale (BE-CON-39, BE-CON-40, 05 §4.3):
 * Retains NamedParameterJdbcTemplate and native SQL rather than Spring Data JPA because:
 * 1. PostgreSQL JSONB projections: Reads immutable raw JSONB columns (canonical_payload, before_state,
 *    after_state, client_info_masked) without JPA entity lifecycle/dirty-checking overhead.
 * 2. Performance & Immutability: banking.audit_log is an append-only, immutable table (BE-CON-20, BE-DAT-08).
 *    Read-only fixed projections with deterministic time-bound window filtering avoid ORM entity cache bloat.
 * 3. Parameterized & Deterministic: Employs strict parameterized predicates and sort whitelisting to guarantee
 *    predictable SQL execution and tie-break ordering.
 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditQueryJdbcStore implements AuditQueryPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AuditQueryJdbcStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public AuditQueryJdbcStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public AuditRecordPage search(AuditSearchQuery query) {
        return search(query, query.reference(), query.correlationId(), clock.instant());
    }

    @Override
    public AuditRecordPage search(AuditSearchQuery query, Instant asOf) {
        return search(query, query.reference(), query.correlationId(), asOf);
    }

    @Override
    public AuditRecordPage search(AuditSearchQuery query, String reference, String correlationId) {
        return search(query, reference, correlationId, clock.instant());
    }

    @Override
    public AuditRecordPage search(AuditSearchQuery query, String reference, String correlationId, Instant asOf) {
        var params = parameters(query).addValue("reference", reference).addValue("correlationId", correlationId);
        StringBuilder predicate = basePredicate();
        if (reference != null) predicate.append(" AND entity_type = 'TRANSFER' AND entity_id = :reference");
        if (correlationId != null) predicate.append(" AND correlation_id = :correlationId");
        addFilter(predicate, params, "actor_user_id", "actorUserId", query.actorUserId());
        addFilter(predicate, params, "actor_type", "actorType", query.actorType());
        addFilter(predicate, params, "action", "action", query.action());
        addFilter(predicate, params, "entity_type", "entityType", query.entityType());
        addFilter(predicate, params, "entity_id", "entityId", query.entityId());
        addFilter(predicate, params, "outcome", "outcome", query.outcome());
        List<AuditRecordSummary> items = jdbc.query(AuditQuerySql.search(query, predicate.toString()), params, AuditQueryJdbcStore::mapSummary);
        Long total = jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE " + predicate, params, Long.class);
        long count = total == null ? 0 : total;
        int pages = (int) Math.min(Integer.MAX_VALUE, (count + query.size() - 1L) / query.size());
        Instant effectiveAsOf = asOf != null ? asOf : clock.instant();
        return new AuditRecordPage(items, query.page(), query.size(), count, pages, query.page() == 0,
                query.page() >= Math.max(0, pages - 1), effectiveAsOf);
    }

    @Override
    public Optional<AuditRecordDetail> findDetail(long auditId) {
        if (auditId <= 0) return Optional.empty();
        String sql = "SELECT audit_id, occurred_at, recorded_at, actor_type, actor_user_id, actor_role, action, "
                + "entity_type, entity_id, amount, currency, correlation_id, request_id, outcome, "
                + "before_state::text AS before_state, after_state::text AS after_state, "
                + "client_info_masked::text AS client_info_masked, prev_hash, record_hash, hash_version "
                + "FROM banking.audit_log WHERE audit_id = ?";
        return jdbc.getJdbcTemplate().query(sql, rs -> rs.next() ? Optional.of(new AuditRecordDetail(mapSummary(rs, 0),
                rs.getString("request_id"), rs.getString("before_state"), rs.getString("after_state"),
                rs.getString("client_info_masked"))) : Optional.empty(), auditId);
    }

    private static MapSqlParameterSource parameters(AuditSearchQuery query) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(query.from())).addValue("to", Timestamp.from(query.to()))
                .addValue("size", query.size()).addValue("offset", query.offset());
    }
    private static StringBuilder basePredicate() { return new StringBuilder("occurred_at >= :from AND occurred_at < :to"); }
    private static void addFilter(StringBuilder predicate, MapSqlParameterSource params, String column, String parameter, Object value) {
        if (value != null) { predicate.append(" AND ").append(column).append(" = :").append(parameter); params.addValue(parameter, value); }
    }
    private static AuditRecordSummary mapSummary(ResultSet rs, int row) throws SQLException {
        String prevHash = rs.getString("prev_hash");
        String recordHash = rs.getString("record_hash");
        return new AuditRecordSummary(Long.toString(rs.getLong("audit_id")), instant(rs, "occurred_at"), instant(rs, "recorded_at"),
                rs.getString("actor_type"), nullableLong(rs, "actor_user_id"), rs.getString("actor_role"), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), decimal(rs.getBigDecimal("amount")), rs.getString("currency"),
                rs.getString("correlation_id"), rs.getString("outcome"),
                validateHash(prevHash, "prev_hash"),
                validateHash(recordHash, "record_hash"),
                rs.getInt("hash_version"));
    }
    static String validateHash(String hash, String column) {
        if (hash == null) return null;
        if (!hash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("Stored " + column + " has invalid hash format");
        }
        return hash;
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp t = rs.getTimestamp(column); return t == null ? null : t.toInstant(); }
    private static String nullableLong(ResultSet rs, String column) throws SQLException { long v = rs.getLong(column); return rs.wasNull() ? null : Long.toString(v); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }
}
