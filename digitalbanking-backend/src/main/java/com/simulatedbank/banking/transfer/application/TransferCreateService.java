package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import java.util.Objects;

/**
 * Single application service behind POST /api/v1/transfers. Re-checks permission at the service
 * boundary (defense in depth, BE-CON-24), emits C3 for security denials, then delegates the money
 * path to the orchestrator. Holds no transaction and never runs T2 on an HTTP retry.
 */
public final class TransferCreateService {
    private final TransferOrchestrator orchestrator;
    private final SecurityDenyAudit denyAudit;

    public TransferCreateService(TransferOrchestrator orchestrator, SecurityDenyAudit denyAudit) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.denyAudit = Objects.requireNonNull(denyAudit, "denyAudit");
    }

    public TransferCreateOutcome create(TransferActor actor, TransferCreateInput.Validated input) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(input, "input");
        if (actor.customerId() == null || !actor.hasAuthority("ROLE_CUSTOMER")
                || !actor.hasAuthority("TRANSFER_CREATE")) {
            denyAudit.accessDenied(actor, "TRANSFER", null, "PERMISSION_DENIED", "TRANSFER_CREATE");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        TransferCreateOutcome outcome = Objects.requireNonNull(
                orchestrator.create(new TransferCreateCommand(actor, input)), "orchestrator outcome");
        if (outcome instanceof TransferCreateOutcome.SourceNotOwned) {
            denyAudit.accessDenied(actor, "ACCOUNT", Long.toString(input.sourceAccountId()),
                    "OWNERSHIP_DENIED", "TRANSFER_CREATE");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (outcome instanceof TransferCreateOutcome.CustomerInactive) {
            denyAudit.accessDenied(actor, "CUSTOMER", Long.toString(actor.customerId()),
                    "CUSTOMER_INACTIVE", "TRANSFER_CREATE");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (outcome instanceof TransferCreateOutcome.Conflict) {
            denyAudit.idempotencyConflict(actor);
        }
        return outcome;
    }
}
