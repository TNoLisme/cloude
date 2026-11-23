package com.simulatedbank.banking.transfer.application;

import java.util.List;
import java.util.Objects;

/** GET receipt projection: masked transfer plus fraud flags populated only for authorized staff. */
public record TransferDetailView(TransferView transfer, List<FraudFlagView> fraudFlags) {
    public TransferDetailView {
        Objects.requireNonNull(transfer, "transfer");
        fraudFlags = List.copyOf(fraudFlags);
    }
}