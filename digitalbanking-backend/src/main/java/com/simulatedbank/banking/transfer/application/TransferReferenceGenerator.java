package com.simulatedbank.banking.transfer.application;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generates transfer references in the proposed OQ-02 shape {@code TXN[0-9]{8}[A-Z0-9]{8,20}}.
 * The API owner has not frozen the format; uniqueness is ultimately enforced by the database
 * constraint, and a reference collision is a distinct unique error, never an idempotency replay.
 */
public final class TransferReferenceGenerator {
    private static final String DIGITS = "0123456789";
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int NUMERIC_LENGTH = 8;
    private static final int SUFFIX_LENGTH = 12;

    private final SecureRandom random;

    public TransferReferenceGenerator() {
        this(new SecureRandom());
    }

    public TransferReferenceGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String generate() {
        StringBuilder reference = new StringBuilder("TXN");
        for (int i = 0; i < NUMERIC_LENGTH; i++) {
            reference.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            reference.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return reference.toString();
    }
}