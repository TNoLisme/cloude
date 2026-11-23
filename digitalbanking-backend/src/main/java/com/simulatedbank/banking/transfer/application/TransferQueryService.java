package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * Read-only receipt query (API-TRF-02). Customer participants are verified through the Account
 * provider (source OR destination ownership), never inferred from stored FK/customer_id. Staff read
 * follows the 07 §3.5 matrix and delegates the data-scope decision to a server-side port.
 * Fraud flags are exposed only to staff holding FRAUD_READ/FRAUD_REVIEW. Existing-but-out-of-scope
 * yields 403 + C3; absent yields 404 (DEC-TRF-012).
 */
public final class TransferQueryService {
    private final TransferReadPort readPort;
    private final AccountOwnershipPort ownershipPort;
    private final FraudFlagReadPort fraudPort;
    private final SecurityDenyAudit denyAudit;
    private final TransferStaffScope staffScope;

    public TransferQueryService(TransferReadPort readPort, AccountOwnershipPort ownershipPort,
                                FraudFlagReadPort fraudPort, SecurityDenyAudit denyAudit, TransferStaffScope staffScope) {
        this.readPort = Objects.requireNonNull(readPort, "readPort");
        this.ownershipPort = Objects.requireNonNull(ownershipPort, "ownershipPort");
        this.fraudPort = Objects.requireNonNull(fraudPort, "fraudPort");
        this.denyAudit = Objects.requireNonNull(denyAudit, "denyAudit");
        this.staffScope = Objects.requireNonNull(staffScope, "staffScope");
    }

    public TransferDetailView get(String reference, TransferActor actor) {
        return read(reference, actor, true);
    }

    public TransferView getReceipt(String reference, TransferActor actor) {
        return read(reference, actor, false).transfer();
    }

    private TransferDetailView read(String reference, TransferActor actor, boolean includeFraud) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actor, "actor");
        boolean staff = !actor.hasAuthority("ROLE_CUSTOMER") && actor.hasAuthority("TRANSACTION_READ")
                && (actor.hasAuthority("ROLE_OPERATIONS") || actor.hasAuthority("ROLE_ADMIN")
                    || actor.hasAuthority("ROLE_AUDITOR"));
        boolean customerSelf = actor.customerId() != null && actor.hasAuthority("ROLE_CUSTOMER")
                && actor.hasAuthority("TRANSACTION_READ_SELF");
        if (!staff && !customerSelf) {
            denyAudit.accessDenied(actor, "TRANSFER", reference, "PERMISSION_DENIED", "TRANSACTION_READ");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        TransferView view = readPort.findByReference(reference)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.TRANSFER_NOT_FOUND));
        if (staff) {
            if (!staffScope.allowsRead(actor.userId(), view.transferId())) {
                denyAudit.accessDenied(actor, "TRANSFER", reference, "SCOPE_DENIED", "TRANSACTION_READ");
                throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            }
            boolean fraudVisible = includeFraud && (actor.hasAuthority("FRAUD_READ") || actor.hasAuthority("FRAUD_REVIEW"));
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
