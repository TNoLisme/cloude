package com.simulatedbank.banking.fraud.adapter.out;

import com.simulatedbank.banking.fraud.api.FraudFlagSummaryQuery;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.fraud.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcFraudFlagSummaryQuery implements FraudFlagSummaryQuery {
    private final JdbcTemplate jdbc;

    public JdbcFraudFlagSummaryQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Flag> findByTransfer(long transferId) {
        return jdbc.query("""
                SELECT flag_id, rule_code, rule_version, severity, status
                FROM banking.fraud_flag WHERE transfer_id = ?
                ORDER BY flag_id
                """, (row, index) -> new Flag(row.getLong("flag_id"), row.getString("rule_code"),
                row.getInt("rule_version"), row.getString("severity"), row.getString("status")), transferId);
    }
}
