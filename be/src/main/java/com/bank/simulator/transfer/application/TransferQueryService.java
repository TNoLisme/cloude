package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.bank.simulator.shared.pagination.CursorPosition;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransferQueryService {
    private final TransferJdbcRepository transfers;
    private final AccountJdbcRepository accounts;
    private final IdentityJdbcRepository identities;
    private final CursorCodec cursors;

    public TransferQueryService(TransferJdbcRepository transfers, AccountJdbcRepository accounts,
                                IdentityJdbcRepository identities) {
        this(transfers, accounts, identities, new CursorCodec(new ObjectMapper()));
    }

    public TransferQueryService(TransferJdbcRepository transfers, AccountJdbcRepository accounts,
                                IdentityJdbcRepository identities, CursorCodec cursors) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.identities = identities;
        this.cursors = cursors;
    }

    public TransferView get(AuthenticatedActor actor, UUID transferId) {
        var transfer = transfers.find(transferId);
        if (transfer == null) throw notFound();
        var source = accounts.find(transfer.sourceAccountId());
        var destination = accounts.find(transfer.destinationAccountId());
        UUID customerId = customerId(actor);
        boolean sourceOwner = source != null && customerId.equals(source.customerId());
        boolean destinationOwner = destination != null && customerId.equals(destination.customerId());
        if (!sourceOwner && (!destinationOwner || !"COMPLETED".equals(transfer.status()))) throw notFound();
        return TransferView.from(transfer, sourceOwner,
                sourceOwner ? counterparty(destination) : counterparty(source));
    }

    public HistoryPage list(AuthenticatedActor actor, HistoryQuery query) {
        UUID customerId = customerId(actor);
        var customerAccounts = accounts.findByCustomer(customerId);
        var accountIds = customerAccounts.stream().map(AccountJdbcRepository.AccountRow::id).toList();
        CursorPosition cursor = decodeCursor(query.cursor());
        List<TransferJdbcRepository.TransferRow> rows = transfers.findHistory(accountIds, query.status(), query.from(), query.to(),
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(), query.limit() + 1);
        boolean hasNext = rows.size() > query.limit();
        List<TransferView> items = rows.stream().limit(query.limit()).map(row -> {
            boolean sourceOwner = accountIds.contains(row.sourceAccountId());
            var otherAccount = accounts.find(sourceOwner ? row.destinationAccountId() : row.sourceAccountId());
            return TransferView.from(row, sourceOwner, counterparty(otherAccount));
        }).toList();
        String next = hasNext ? cursors.encode(items.getLast().cursorPosition()) : null;
        return new HistoryPage(items, next);
    }

    public List<TransferView> list(AuthenticatedActor actor) {
        return list(actor, new HistoryQuery(100, null, null, null, null)).items();
    }

    public CursorPosition decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            return cursors.decode(cursor);
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer cursor is invalid.");
        }
    }

    private Counterparty counterparty(AccountJdbcRepository.AccountRow account) {
        if (account == null) return new Counterparty("••••", "Customer");
        var customer = identities.findCustomerById(account.customerId());
        return new Counterparty(account.maskedNumber(), customer == null ? "Customer" : customer.fullName());
    }

    private UUID customerId(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        return customer.customerId();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer is not available.");
    }

    private record Counterparty(String masked, String name) {}

    public record HistoryQuery(int limit, String cursor, String status, Instant from, Instant to) {
        public HistoryQuery {
            if (limit < 1 || limit > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer limit is invalid.");
            }
            if (from != null && to != null && !from.isBefore(to)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "From must be before to.");
            }
        }
    }

    public record HistoryPage(List<TransferView> items, String nextCursor) {}

    public record TransferView(UUID transferId, String status, String failureCode, UUID sourceAccountId,
                               UUID destinationAccountId, String amount, String currency, String memo,
                               Instant createdAt, Instant expiresAt, Instant completedAt,
                               String direction, String counterpartyAccountMasked, String counterpartyDisplayName) {
        static TransferView from(TransferJdbcRepository.TransferRow row, boolean sourceOwner, Counterparty counterparty) {
            return new TransferView(row.id(), row.status(), row.failureCode(), row.sourceAccountId(),
                    row.destinationAccountId(), row.amount(), row.currency(), row.memo(), row.createdAt(),
                    row.expiresAt(), row.completedAt(), sourceOwner ? "OUTGOING" : "INCOMING",
                    counterparty.masked(), counterparty.name());
        }

        CursorPosition cursorPosition() {
            return new CursorPosition(1, createdAt, transferId);
        }
    }
}
