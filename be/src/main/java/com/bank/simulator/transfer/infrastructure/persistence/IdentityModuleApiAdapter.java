package com.bank.simulator.transfer.infrastructure.persistence;

import com.bank.simulator.identity.application.PinCredentialService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class IdentityModuleApiAdapter implements IdentityModuleApi {
    private final PinCredentialService pinCredentials;
    private final IdentityJdbcRepository identities;

    public IdentityModuleApiAdapter(PinCredentialService pinCredentials, IdentityJdbcRepository identities) {
        this.pinCredentials = pinCredentials;
        this.identities = identities;
    }

    @Override
    public PinVerificationResult verifyPin(UUID customerId, String pin) {
        return switch (pinCredentials.verify(customerId, pin)) {
            case VALID -> PinVerificationResult.VALID;
            case INVALID -> PinVerificationResult.INVALID;
            case LOCKED -> PinVerificationResult.LOCKED;
            case NOT_SET -> PinVerificationResult.NOT_SET;
        };
    }

    @Override
    public CustomerView findCustomerByUserId(UUID userId) {
        return toView(identities.findCustomerByUserId(userId));
    }

    @Override
    public CustomerView findCustomerById(UUID customerId) {
        return toView(identities.findCustomerById(customerId));
    }

    private CustomerView toView(IdentityJdbcRepository.CustomerRecord record) {
        if (record == null) return null;
        return new CustomerView(record.customerId(), record.userId(), record.fullName(), record.phone(), record.pinSet());
    }
}
