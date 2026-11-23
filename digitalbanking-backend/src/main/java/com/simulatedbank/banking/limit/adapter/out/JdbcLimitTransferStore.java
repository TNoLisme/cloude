package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.api.TransferLimits.Reservation;
import com.simulatedbank.banking.limit.api.TransferLimits.ReservationState;
import com.simulatedbank.banking.limit.api.TransferLimits.Scope;
import com.simulatedbank.banking.limit.api.TransferLimits.ScopeEffect;
import com.simulatedbank.banking.limit.api.TransferLimits.TransferLink;
import com.simulatedbank.banking.limit.application.port.LimitTransferStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
class JdbcLimitTransferStore implements LimitTransferStore {
    private final JdbcTemplate jdbc;

    @Override
    public BigDecimal pendingAmount(long customerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM banking.limit_transfer_reservation
                WHERE customer_id = ? AND state = 'PENDING'
                """, BigDecimal.class, customerId);
    }

    @Override
    public void insertReservation(Reservation reservation, Instant gateAt) {
        var transfer = reservation.transfer();
        jdbc.update("""
                INSERT INTO banking.limit_transfer_reservation
                    (transfer_id, customer_id, source_account_id, amount, currency,
                     transfer_created_at, gate_at, t1_date, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transfer.transferId(), transfer.customerId(), transfer.sourceAccountId(), transfer.amount(),
                transfer.currency(), timestamp(transfer.createdAt()), timestamp(gateAt),
                reservation.t1Date(), reservation.policyVersion());
    }

    @Override
    public Optional<StoredReservation> findReservation(long transferId) {
        return jdbc.query("""
                SELECT transfer_id, customer_id, source_account_id, amount, currency,
                       transfer_created_at, gate_at, t1_date, policy_version,
                       state, terminal_at, terminal_date, effect_count
                FROM banking.limit_transfer_reservation WHERE transfer_id = ?
                """, (row, index) -> {
                    var transfer = new TransferLink(row.getLong("transfer_id"), row.getLong("customer_id"),
                            row.getLong("source_account_id"), row.getBigDecimal("amount"),
                            row.getString("currency"), instant(row, "transfer_created_at"));
                    var reservation = new Reservation(transfer, row.getObject("t1_date", LocalDate.class),
                            row.getString("policy_version"));
                    return new StoredReservation(reservation, instant(row, "gate_at"),
                            ReservationState.valueOf(row.getString("state")), instant(row, "terminal_at"),
                            row.getObject("terminal_date", LocalDate.class), row.getObject("effect_count", Integer.class));
                }, transferId).stream().findFirst();
    }

    @Override
    public boolean finish(long transferId, ReservationState state, Instant terminalAt, LocalDate date, int effectCount) {
        return jdbc.update("""
                UPDATE banking.limit_transfer_reservation
                SET state = ?, terminal_at = ?, terminal_date = ?, effect_count = ?
                WHERE transfer_id = ? AND state = 'PENDING'
                """, state.name(), timestamp(terminalAt), date, effectCount, transferId) == 1;
    }

    @Override
    public void insertEffect(long transferId, ScopeEffect effect) {
        jdbc.update("""
                INSERT INTO banking.limit_transfer_usage_effect
                    (transfer_id, scope, usage_id, config_id, config_version, per_transaction_limit, daily_limit,
                     amount_delta, count_delta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transferId, effect.scope().name(), effect.usageId(), effect.configId(), effect.configVersion(),
                effect.perTransactionLimit(), effect.dailyLimit(), effect.amount(), effect.count());
    }

    @Override
    public List<ScopeEffect> findEffects(long transferId) {
        return jdbc.query("""
                SELECT scope, usage_id, config_id, config_version, per_transaction_limit, daily_limit, amount_delta, count_delta
                FROM banking.limit_transfer_usage_effect WHERE transfer_id = ?
                ORDER BY CASE scope WHEN 'CUSTOMER' THEN 0 ELSE 1 END
                """, (row, index) -> new ScopeEffect(Scope.valueOf(row.getString("scope")), row.getLong("usage_id"),
                row.getLong("config_id"), row.getInt("config_version"), row.getBigDecimal("per_transaction_limit"),
                row.getBigDecimal("daily_limit"), row.getBigDecimal("amount_delta"), row.getInt("count_delta")), transferId);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
