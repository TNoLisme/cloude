package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.api.AccountBalance.BalanceAfter;
import com.simulatedbank.banking.account.api.AccountTransferEvidence;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAccountTransferEvidence implements AccountTransferEvidence {
    private final JdbcTemplate jdbc;

    public JdbcAccountTransferEvidence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordMovement(Movement movement) {
        int inserted = jdbc.update("""
                INSERT INTO banking.account_transfer_movement
                    (transfer_id, source_account_id, destination_account_id, amount, currency,
                     source_available_after, destination_available_after, source_ledger_after, destination_ledger_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, movement.transferId(), movement.sourceId(), movement.destinationId(), movement.amount(),
                movement.currency(), movement.sourceAfter().availableBalance(), movement.destinationAfter().availableBalance(),
                movement.sourceAfter().ledgerBalance(), movement.destinationAfter().ledgerBalance());
        if (inserted != 1) throw new IllegalStateException("Account movement witness was not recorded");
    }

    @Override
    public Optional<Movement> findMovement(long transferId) {
        return DataAccessUtils.optionalResult(jdbc.query("""
                SELECT * FROM banking.account_transfer_movement WHERE transfer_id = ?
                """, (rs, row) -> new Movement(rs.getLong("transfer_id"), rs.getLong("source_account_id"),
                rs.getLong("destination_account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                new BalanceAfter(rs.getBigDecimal("source_available_after"), rs.getBigDecimal("source_ledger_after")),
                new BalanceAfter(rs.getBigDecimal("destination_available_after"), rs.getBigDecimal("destination_ledger_after"))),
                transferId));
    }
}
