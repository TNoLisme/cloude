package com.simulatedbank.banking.transfer.adapter.out.persistence;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.transfer.application.MaskedAccount;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import com.simulatedbank.banking.transfer.application.TransferView;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcTransferRead implements TransferReadPort {
    private final JdbcTemplate jdbc;
    private final AccountQuery accounts;

    public JdbcTransferRead(JdbcTemplate jdbc, AccountQuery accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Override
    public Optional<TransferView> findByReference(String reference) {
        return DataAccessUtils.optionalResult(jdbc.query("""
                SELECT transfer_id, reference_no, status, amount, currency, description,
                       source_account_id, destination_account_id, correlation_id, created_at,
                       completed_at, failed_at, failure_code, failure_message
                FROM banking.transfer WHERE reference_no = ?
                """, JdbcTransferRead::map, reference)).map(this::receipt);
    }

    private record StoredTransfer(long id, String reference, TransferStatus status, TransferAmount amount,
                                 String description, long source, long destination, String correlationId,
                                 Instant createdAt, Instant completedAt, Instant failedAt,
                                 TransferFailureCode failureCode, String failureMessage) {}

    private static StoredTransfer map(ResultSet row, int index) throws SQLException {
        String failureCode = row.getString("failure_code");
        return new StoredTransfer(row.getLong("transfer_id"), row.getString("reference_no"),
                TransferStatus.valueOf(row.getString("status")),
                new TransferAmount(row.getBigDecimal("amount"), row.getString("currency")),
                row.getString("description"), row.getLong("source_account_id"),
                row.getLong("destination_account_id"), row.getString("correlation_id"),
                instant(row, "created_at"), instant(row, "completed_at"), instant(row, "failed_at"),
                failureCode == null ? null : TransferFailureCode.valueOf(failureCode), row.getString("failure_message"));
    }

    private TransferView receipt(StoredTransfer transfer) {
        return new TransferView(transfer.id(), transfer.reference(), transfer.status(), transfer.amount(),
                transfer.description(), maskedAccount(transfer.source()), maskedAccount(transfer.destination()),
                transfer.correlationId(), transfer.createdAt(), transfer.completedAt(), transfer.failedAt(),
                transfer.failureCode(), transfer.failureMessage());
    }

    private MaskedAccount maskedAccount(long accountId) {
        var account = accounts.findAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("Transfer account projection is missing"));
        return new MaskedAccount(accountId, account.accountNumberMasked(), account.accountNumberLast4());
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getObject(column, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
