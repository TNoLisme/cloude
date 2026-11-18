package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import java.util.Objects;

/** Server-bound create command: authenticated actor plus the validated, normalized request. */
public record TransferCreateCommand(TransferActor actor, TransferCreateInput.Validated input) {
    public TransferCreateCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(input, "input");
    }

    @Override
    public String toString() {
        return "TransferCreateCommand[redacted]";
    }
}