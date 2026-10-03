package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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

    public List<TransferView> list(AuthenticatedActor actor) {
        UUID customerId = customerId(actor);
        var customerAccounts = accounts.findByCustomer(customerId);
        return customerAccounts.stream().flatMap(account -> transfers.findByAccount(account.id()).stream())
                .map(TransferView::from).distinct().toList();
    }

    private UUID customerId(AuthenticatedActor actor) {
        var customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        return customer.customerId();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer is not available.");
    }

    public record TransferView(UUID transferId, String status, String failureCode, UUID sourceAccountId,
                               UUID destinationAccountId, String amount, String currency, String memo,
                               java.time.Instant createdAt, java.time.Instant expiresAt,
                               java.time.Instant completedAt) {
        static TransferView from(TransferJdbcRepository.TransferRow row) {
            return new TransferView(row.id(), row.status(), row.failureCode(), row.sourceAccountId(),
                    row.destinationAccountId(), row.amount(), row.currency(), row.memo(), row.createdAt(),
                    row.expiresAt(), row.completedAt());
        }
    }
}
