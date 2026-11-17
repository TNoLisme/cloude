package com.simulatedbank.banking.transfer.application;

/**
 * Seam for the BE-TRF-04/05 orchestrator. Implementations must look up the global idempotency
 * key and classify actor/customer/hash BEFORE reading mutable account/limit state, so a terminal
 * replay wins even if the destination later became inactive (CT-API-007, DEC-TRF-004). Destination
 * resolution and source ownership use the Account provider (TRFC-AC-01/02); no outer transaction
 * may wrap T0-T2. The boundary re-checks permission and emits C3 for security denials.
 */
@FunctionalInterface
public interface TransferOrchestrator {
    TransferCreateOutcome create(TransferCreateCommand command);
}