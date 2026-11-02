package com.bank.simulator.customer.infrastructure;

import java.security.SecureRandom;
import java.util.Objects;

public final class AccountNumberGenerator {

    private final SecureRandom secureRandom;

    public AccountNumberGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    public String generate() {
        long value = secureRandom.nextLong(1_000_000_000_000L);
        return "%012d".formatted(value);
    }
}
