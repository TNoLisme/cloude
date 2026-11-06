package com.bank.simulator.identity.infrastructure.security;

public class RefreshSessionReuseException extends IllegalArgumentException {

    public RefreshSessionReuseException(String message) {
        super(message);
    }
}
