package com.simulatedbank.banking.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PiiCryptoService {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PiiCryptoService(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES key must contain 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    public byte[] encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext is required");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] blob = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
            return blob;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PII encryption failed");
        }
    }

    public String decrypt(byte[] blob) {
        Objects.requireNonNull(blob, "ciphertext is required");
        if (blob.length < 28) {
            throw new IllegalArgumentException("Invalid encrypted PII");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 0, 12));
            // One-shot authentication: never release plaintext before tag verification.
            return new String(cipher.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid encrypted PII");
        }
    }
}
