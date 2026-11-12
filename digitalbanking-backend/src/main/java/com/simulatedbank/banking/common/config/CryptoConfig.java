package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CryptoConfig {
    @Bean
    public PiiCryptoService piiCryptoService(AppProperties properties, Environment environment) {
        byte[][] keys = validatedKeys(properties.crypto(), environment);
        try {
            return new PiiCryptoService(keys[0]);
        } finally {
            clear(keys);
        }
    }

    @Bean
    public HmacService hmacService(AppProperties properties, Environment environment) {
        byte[][] keys = validatedKeys(properties.crypto(), environment);
        try {
            return new HmacService(keys[1]);
        } finally {
            clear(keys);
        }
    }

    private static byte[][] validatedKeys(AppProperties.Crypto crypto, Environment environment) {
        byte[] aes = decode(crypto.aesKeyBase64());
        byte[] hmac = null;
        try {
            hmac = decode(crypto.hmacKeyBase64());
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length == 0) {
                profiles = environment.getDefaultProfiles();
            }
            boolean development = profiles.length > 0 && Arrays.stream(profiles)
                    .allMatch(Set.of("local", "test")::contains);
            if (MessageDigest.isEqual(aes, hmac)
                    || (!development && (isPlaceholder(aes) || isPlaceholder(hmac)))) {
                throw new IllegalArgumentException("Invalid crypto key configuration");
            }
            if (!crypto.previousKeys().isEmpty()) {
                throw new IllegalArgumentException("Crypto key rotation is not supported");
            }
            return new byte[][] {aes, hmac};
        } catch (RuntimeException ex) {
            Arrays.fill(aes, (byte) 0);
            if (hmac != null) {
                Arrays.fill(hmac, (byte) 0);
            }
            throw ex;
        }
    }

    private static byte[] decode(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (key.length == 32) {
                return key;
            }
            Arrays.fill(key, (byte) 0);
        } catch (IllegalArgumentException ex) {
            // Do not retain decoder diagnostics or rejected values in startup exceptions.
        }
        throw new IllegalArgumentException("Crypto keys must be Base64-encoded 32-byte values");
    }

    private static boolean isPlaceholder(byte[] key) {
        String encoded = Base64.getEncoder().encodeToString(key);
        return encoded.equals("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                || encoded.equals("MTEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                || java.util.stream.IntStream.range(1, key.length).allMatch(i -> key[i] == key[0]);
    }

    private static void clear(byte[][] keys) {
        Arrays.stream(keys).forEach(key -> Arrays.fill(key, (byte) 0));
    }
}
