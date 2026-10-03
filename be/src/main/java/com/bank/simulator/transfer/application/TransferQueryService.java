package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

@Service
public class TransferQueryService {
    private final TransferJdbcRepository transfers;
    private final AccountJdbcRepository accounts;
    private final IdentityJdbcRepository identities;

    public TransferQueryService(TransferJdbcRepository transfers, AccountJdbcRepository accounts,
                                IdentityJdbcRepository identities) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.identities = identities;
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
        return TransferView.from(transfer);
    }

    public HistoryPage list(AuthenticatedActor actor, HistoryQuery query) {
        UUID customerId = customerId(actor);
        var customerAccounts = accounts.findByCustomer(customerId);
        var accountIds = customerAccounts.stream().map(AccountJdbcRepository.AccountRow::id).collect(java.util.stream.Collectors.toSet());
        List<TransferView> filtered = customerAccounts.stream()
                .flatMap(account -> transfers.findHistory(account.id(), query.status(), query.from(), query.to(),
                        query.cursor() == null ? null : decodeCursor(query.cursor()).createdAt(),
                        query.cursor() == null ? null : decodeCursor(query.cursor()).transferId(), query.limit() + 1).stream())
                .distinct()
                .filter(row -> query.status() == null || query.status().equals(row.status()))
                .filter(row -> query.from() == null || !row.createdAt().isBefore(query.from()))
                .filter(row -> query.to() == null || !row.createdAt().isAfter(query.to()))
                .sorted(java.util.Comparator.comparing(TransferJdbcRepository.TransferRow::createdAt).reversed()
                        .thenComparing(TransferJdbcRepository.TransferRow::id, java.util.Comparator.reverseOrder()))
                .map(row -> {
                    boolean sourceOwner = accountIds.contains(row.sourceAccountId());
                    UUID otherAccountId = sourceOwner ? row.destinationAccountId() : row.sourceAccountId();
                    var otherAccount = accounts.find(otherAccountId);
                    var otherCustomer = otherAccount == null ? null : identities.findCustomerById(otherAccount.customerId());
                    String displayName = otherCustomer == null || otherCustomer.fullName().isBlank()
                            ? "Customer" : otherCustomer.fullName();
                    return TransferView.from(row, sourceOwner,
                            otherAccount == null ? "••••" : otherAccount.maskedNumber(), displayName);
                })
                .toList();
        if (query.cursor() != null) {
            Cursor cursor = decodeCursor(query.cursor());
            filtered = filtered.stream().filter(item -> item.createdAt().isBefore(cursor.createdAt())
                    || item.createdAt().equals(cursor.createdAt()) && item.transferId().compareTo(cursor.transferId()) < 0).toList();
        }
        List<TransferView> pageItems = filtered.subList(0, Math.min(query.limit(), filtered.size()));
        String next = filtered.size() > query.limit() ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new HistoryPage(pageItems, next);
    }

    public List<TransferView> list(AuthenticatedActor actor) {
        return list(actor, new HistoryQuery(100, null, null, null, null)).items();
    }

    private String encodeCursor(TransferView item) {
        byte[] id = ByteBuffer.allocate(16).putLong(item.transferId().getMostSignificantBits()).putLong(item.transferId().getLeastSignificantBits()).array();
        byte[] time = ByteBuffer.allocate(Long.BYTES).putLong(item.createdAt().toEpochMilli()).array();
        byte[] value = new byte[time.length + id.length];
        System.arraycopy(time, 0, value, 0, time.length);
        System.arraycopy(id, 0, value, time.length, id.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private Cursor decodeCursor(String cursor) {
        try {
            byte[] value = Base64.getUrlDecoder().decode(cursor);
            if (value.length != 24) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(value);
            Instant createdAt = Instant.ofEpochMilli(buffer.getLong());
            UUID transferId = new UUID(buffer.getLong(), buffer.getLong());
            return new Cursor(createdAt, transferId);
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer cursor is invalid.");
        }
    }

    private record Cursor(Instant createdAt, UUID transferId) {}

    private UUID customerId(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        return customer.customerId();
    }

    public record HistoryQuery(int limit, String cursor, String status, Instant from, Instant to) {
        public HistoryQuery {
            if (limit < 1 || limit > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer limit is invalid.");
            }
            if (from != null && to != null && from.isAfter(to)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer date range is invalid.");
            }
        }
    }

    public record HistoryPage(List<TransferView> items, String nextCursor) {}
    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer is not available.");
    }

    public record TransferView(UUID transferId, String status, String failureCode, UUID sourceAccountId,
                               UUID destinationAccountId, String amount, String currency, String memo,
                               Instant createdAt, Instant expiresAt, Instant completedAt,
                               String direction, String counterpartyAccountMasked, String counterpartyDisplayName) {
        static TransferView from(TransferJdbcRepository.TransferRow row) {
            return from(row, true);
        }

        static TransferView from(TransferJdbcRepository.TransferRow row, boolean sourceOwner, String masked, String displayName) {
            return new TransferView(row.id(), row.status(), row.failureCode(), row.sourceAccountId(),
                    row.destinationAccountId(), row.amount(), row.currency(), row.memo(), row.createdAt(),
                    row.expiresAt(), row.completedAt(), sourceOwner ? "OUTGOING" : "INCOMING", masked, displayName);
        }

        static TransferView from(TransferJdbcRepository.TransferRow row, boolean sourceOwner) {
            return from(row, sourceOwner, null, null);
        }
    }
}
