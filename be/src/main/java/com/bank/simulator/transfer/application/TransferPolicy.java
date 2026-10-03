package com.bank.simulator.transfer.application;

public final class TransferPolicy {
    private TransferPolicy() {}

    public static final long MIN_AMOUNT = 2_000L;
    public static final long MAX_AMOUNT = 10_000_000L;
    public static final long OTP_THRESHOLD = 5_000_000L;

    public static long parseAmount(String amount) {
        if (amount == null || !amount.matches("(?:[2-9][0-9]{3}|[1-9][0-9]{4,6}|10000000)")) {
            throw new IllegalArgumentException("Amount must be canonical VND integer in range 2000..10000000");
        }
        return Long.parseLong(amount);
    }

    public static boolean requiresOtp(long amount) {
        return amount > OTP_THRESHOLD;
    }
}
