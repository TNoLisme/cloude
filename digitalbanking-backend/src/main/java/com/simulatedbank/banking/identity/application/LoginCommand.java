package com.simulatedbank.banking.identity.application;

/** Primary credentials and untrusted device metadata; metadata never grants MFA bypass. */
public record LoginCommand(String username, char[] password, java.util.UUID deviceRef,
        String deviceName, String deviceFingerprint) {

    @Override
    public String toString() {
        return "LoginCommand[redacted]";
    }
}
