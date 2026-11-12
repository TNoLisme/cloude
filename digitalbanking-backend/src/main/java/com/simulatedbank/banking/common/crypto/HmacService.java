package com.simulatedbank.banking.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacService {
    private final SecretKeySpec key;

    public HmacService(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("HMAC key must contain 32 bytes");
        }
        this.key = new SecretKeySpec(key, "HmacSHA256");
    }

    public String hmacSha256Hex(String input) {
        Objects.requireNonNull(input, "input is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC operation failed");
        }
    }

    public static String sha256Hex(String input) {
        Objects.requireNonNull(input, "input is required");
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        Objects.requireNonNull(input, "input is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 operation failed");
        }
    }

    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        Objects.requireNonNull(expected, "expected is required");
        Objects.requireNonNull(actual, "actual is required");
        return MessageDigest.isEqual(expected, actual);
    }
}
