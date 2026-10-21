package com.bank.simulator.transfer.api;

import java.util.UUID;

/**
 * Outbound port to the identity module. The transfer module only talks to
 * identity through this interface; the adapter lives in transfer infrastructure.
 */
public interface IdentityModuleApi {

    PinVerificationResult verifyPin(UUID customerId, String pin);

    CustomerView findCustomerByUserId(UUID userId);

    CustomerView findCustomerById(UUID customerId);

    enum PinVerificationResult { VALID, INVALID, LOCKED, NOT_SET }

    record CustomerView(UUID customerId, UUID userId, String fullName, String phone, boolean pinSet) {}
}
