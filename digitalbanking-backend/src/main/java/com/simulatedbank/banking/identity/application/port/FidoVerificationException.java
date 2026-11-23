package com.simulatedbank.banking.identity.application.port;

/** Deliberately carries neither input material nor a provider exception/cause. */
public final class FidoVerificationException extends RuntimeException {
    public enum Reason {
        INVALID_ASSERTION, COUNTER_ANOMALY, UNSUPPORTED_ALGORITHM
    }

    private final Reason reason;

    public FidoVerificationException(Reason reason) {
        super("FIDO verification failed");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
