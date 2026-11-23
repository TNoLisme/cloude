package com.simulatedbank.banking.transfer.adapter.out.persistence;

import com.simulatedbank.banking.common.config.ApiPage;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.application.TransactionHistory;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class JdbcTransactionHistory implements TransactionHistory {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTransactionHistory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiPage<Item> list(Filter filter) {
        var window = filter.window();
        var args = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(window.from())).addValue("to", Timestamp.from(window.to()))
                .addValue("limit", window.size()).addValue("offset", window.offset());
        StringBuilder where = new StringBuilder(" WHERE t.created_at >= :from AND t.created_at < :to");
        if (filter.customerId() != null) {
            args.addValue("customer", filter.customerId());
            where.append(" AND (s.customer_id = :customer OR d.customer_id = :customer)");
        }
        String direction = filter.customerId() == null ? "'DEBIT'" : """
                CASE WHEN s.customer_id = :customer AND d.customer_id = :customer THEN 'INTERNAL'
                     WHEN d.customer_id = :customer THEN 'CREDIT' ELSE 'DEBIT' END
                """;
        if (filter.accountId() != null) {
            args.addValue("account", filter.accountId());
            where.append(" AND (s.account_id = :account OR d.account_id = :account)");
            if (filter.accountPerspective()) {
                direction = "CASE WHEN d.account_id = :account THEN 'CREDIT' ELSE 'DEBIT' END";
            }
        }
        if (filter.direction() != null) {
            where.append(" AND (").append(direction).append(") = :direction");
            args.addValue("direction", filter.direction().name());
        }
        if (filter.status() != null) {
            where.append(" AND t.status = :status");
            args.addValue("status", filter.status().name());
        }
        if (filter.minAmount() != null) {
            where.append(" AND t.amount >= :min");
            args.addValue("min", filter.minAmount());
        }
        if (filter.maxAmount() != null) {
            where.append(" AND t.amount <= :max");
            args.addValue("max", filter.maxAmount());
        }

        String tables = """
                FROM banking.transfer t
                JOIN banking.account_history_participant s ON s.account_id = t.source_account_id
                JOIN banking.account_history_participant d ON d.account_id = t.destination_account_id
                """;
        String order = switch (filter.sort()) {
            case "createdAt,desc" -> "t.created_at DESC, t.transfer_id DESC";
            case "createdAt,asc" -> "t.created_at ASC, t.transfer_id ASC";
            case "amount,desc" -> "t.amount DESC, t.transfer_id DESC";
            case "amount,asc" -> "t.amount ASC, t.transfer_id ASC";
            default -> throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        };
        long count = jdbc.queryForObject("SELECT count(*) " + tables + where, args, Long.class);
        var items = jdbc.query("""
                SELECT t.reference_no, t.amount, t.currency, t.status, t.description, t.created_at,
                       t.completed_at, s.account_no_last4 AS source_last4, d.account_no_last4 AS destination_last4,
                """ + direction + " AS direction " + tables + where
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", args, (row, index) -> {
                    Direction relative = Direction.valueOf(row.getString("direction"));
                    String last4 = row.getString(relative == Direction.CREDIT ? "source_last4" : "destination_last4");
                    Timestamp completed = row.getTimestamp("completed_at");
                    return new Item(row.getString("reference_no"), relative, row.getBigDecimal("amount").toPlainString(),
                            row.getString("currency"), row.getString("status"), Counterparty.withLast4(last4),
                            row.getString("description"), row.getTimestamp("created_at").toInstant(),
                            completed == null ? null : completed.toInstant());
                });
        return ApiPage.of(items, count, window);
    }
}
