package com.bank.simulator.identity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CsrfTokenService {

    private final byte[] secret;
    private final SecureRandom secureRandom = new SecureRandom();

    public CsrfTokenService(@Value("${app.security.jwt-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalStateException("app.security.jwt-secret must contain at least 32 UTF-8 bytes");
        }
    }

    public String issue() {
        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);
        byte[] signature = sign(nonce);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    public boolean isValid(String token) {
        if (token == null || token.length() > 256) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[0]);
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            if (!encoder.encodeToString(nonce).equals(parts[0])
                    || !encoder.encodeToString(suppliedSignature).equals(parts[1])) {
                return false;
            }
            return nonce.length == 32 && MessageDigest.isEqual(sign(nonce), suppliedSignature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] sign(byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(nonce);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign CSRF token", exception);
        }
    }
}
