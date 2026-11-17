package com.simulatedbank.banking.transfer.application;

import java.util.Optional;

/** Transfer-owned read model lookup by reference; empty means a successful read found no transfer. */
@FunctionalInterface
public interface TransferReadPort {
    Optional<TransferView> findByReference(String reference);
}