package com.simulatedbank.banking.identity.application.port;

/** Verifies only an assertion; ceremony lifetime, device trust and persistence belong to the caller. */
public interface FidoAssertionVerifier {
    record Assertion(String credentialId, String clientDataJSON, String authenticatorData,
                     String signature, String userHandle) {
        @Override
        public String toString() {
            return "Assertion[REDACTED]";
        }
    }

    record Verified(long signCount) {}

    Verified verify(FidoCredentialStore.Credential credential, String nonce, Assertion assertion);
}
