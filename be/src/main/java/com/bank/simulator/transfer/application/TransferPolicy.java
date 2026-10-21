package com.bank.simulator.transfer.application;

import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class TransferPolicy {
    private TransferPolicy() {}

    public static final BigDecimal MIN_AMOUNT = new BigDecimal("2000");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    public static final BigDecimal OTP_THRESHOLD = new BigDecimal("5000000");

    public static BigDecimal parseAmount(String input) {
        if (input == null || input.isBlank()) throw amountInvalid();
        if (!input.matches("[1-9][0-9]*")) throw amountInvalid();
        BigDecimal amount;
        try {
            amount = new BigDecimal(input);
        } catch (NumberFormatException error) {
            throw amountInvalid();
        }
        if (amount.scale() != 0 || amount.signum() <= 0 || !amount.toPlainString().equals(input)) throw amountInvalid();
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) throw amountInvalid();
        return amount;
    }

    public static boolean requiresOtp(BigDecimal amount) {
        return amount.compareTo(OTP_THRESHOLD) > 0;
    }

    public static String payloadHash(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                                     String currency, String memo) {
        String payload = String.format(
                "sourceAccountId=%s,destinationAccountId=%s,amount=%s,currency=%s,memo=%s",
                sourceAccountId, destinationAccountId, amount.toPlainString(), currency, memo == null ? "" : memo);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static ApiException amountInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID",
                "Amount must be a canonical VND integer between 2000 and 10000000.");
    }
}
