package com.bank.simulator.identity.infrastructure.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class RefreshTokenGenerator {

    private final SecureRandom secureRandom;

    public RefreshTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        byte[] token = new byte[32];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
