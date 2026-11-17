package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * Read-only receipt query (API-TRF-02). Customer participants are verified through the Account
 * provider (source OR destination ownership), never inferred from stored FK/customer_id. Staff read
 * follows the 07 §3.5 matrix; the server-side data-scope mechanism is still pending (Blocked).
 * Fraud flags are exposed only to staff holding FRAUD_READ/FRAUD_REVIEW. Existing-but-out-of-scope
 * yields 403 + C3; absent yields 404 (DEC-TRF-012).
 */
public final class TransferQueryService {
    private final TransferReadPort readPort;
    private final AccountOwnershipPort ownershipPort;
    private final FraudFlagReadPort fraudPort;
    private final SecurityDenyAudit denyAudit;

    public TransferQueryService(TransferReadPort readPort, AccountOwnershipPort ownershipPort,
                                FraudFlagReadPort fraudPort, SecurityDenyAudit denyAudit) {
        this.readPort = Objects.requireNonNull(readPort, "readPort");
        this.ownershipPort = Objects.requireNonNull(ownershipPort, "ownershipPort");
        this.fraudPort = Objects.requireNonNull(fraudPort, "fraudPort");
        this.denyAudit = Objects.requireNonNull(denyAudit, "denyAudit");
    }

    public TransferDetailView get(String reference, TransferActor actor) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actor, "actor");
        boolean staff = actor.hasAuthority("TRANSACTION_READ");
        boolean customerSelf = actor.customerId() != null && actor.hasAuthority("TRANSACTION_READ_SELF");
        if (!staff && !customerSelf) {
            denyAudit.accessDenied(actor, "TRANSFER", reference, "PERMISSION_DENIED", "TRANSACTION_READ");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        TransferView view = readPort.findByReference(reference)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.TRANSFER_NOT_FOUND));
        if (staff) {
            // Server-side data-scope mechanism is still pending (DEC-TRF-012); 07 §3.5 matrix allows read.
            boolean fraudVisible = actor.hasAuthority("FRAUD_READ") || actor.hasAuthority("FRAUD_REVIEW");
            List<FraudFlagView> flags = fraudVisible ? fraudPort.findByTransfer(view.transferId()) : List.of();
            return new TransferDetailView(view, flags);
        }
        boolean participant = ownershipPort.assertOwnership(actor.customerId(), view.source().accountId())
                == AccountOwnershipPort.Ownership.OWNED
                || ownershipPort.assertOwnership(actor.customerId(), view.destination().accountId())
                == AccountOwnershipPort.Ownership.OWNED;
        if (!participant) {
            denyAudit.accessDenied(actor, "TRANSFER", reference, "OWNERSHIP_DENIED", "TRANSACTION_READ_SELF");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return new TransferDetailView(view, List.of());
    }
}