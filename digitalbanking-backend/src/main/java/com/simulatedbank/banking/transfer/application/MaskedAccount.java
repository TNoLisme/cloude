package com.simulatedbank.banking.transfer.application;

/** Masked account projection owned by the Account provider; never a full account number. */
public record MaskedAccount(long accountId, String maskedNumber, String last4) {
    public MaskedAccount {
        if (accountId <= 0) throw new IllegalArgumentException("Invalid account ID");
        if (maskedNumber == null || !maskedNumber.matches("\\*{4} \\*{4} [0-9]{4}")) {
            throw new IllegalArgumentException("Invalid masked account number");
        }
        if (last4 == null || !last4.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("Invalid account last4");
        }
    }
}