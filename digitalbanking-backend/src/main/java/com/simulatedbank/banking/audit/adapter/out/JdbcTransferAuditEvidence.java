package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.api.TransferAuditEvidence;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcTransferAuditEvidence implements TransferAuditEvidence {
    private final JdbcTemplate jdbc;

    public JdbcTransferAuditEvidence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Completion> findCompletions(String reference) {
        return List.copyOf(jdbc.query("""
                SELECT audit_id, action, outcome, after_state ->> 'status' AS status, actor_type, actor_user_id,
                       amount, currency, correlation_id, occurred_at
                FROM banking.audit_log
                WHERE entity_type = 'TRANSFER' AND entity_id = ?
                  AND action IN ('TRANSFER_COMPLETED', 'TRANSFER_FAILED', 'TRANSFER_RECONCILED')
                ORDER BY audit_id LIMIT 2
                """, (rs, row) -> new Completion(rs.getLong("audit_id"), rs.getString("action"),
                rs.getString("outcome"), rs.getString("status"), rs.getString("actor_type"), rs.getObject("actor_user_id", Long.class),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("correlation_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant()), reference));
    }
}
