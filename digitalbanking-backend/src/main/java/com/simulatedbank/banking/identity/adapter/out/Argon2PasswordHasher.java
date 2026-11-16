package com.simulatedbank.banking.identity.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.identity.application.PasswordHashFormatException;
import com.simulatedbank.banking.identity.application.PasswordHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

/**
 * Argon2id theo tham số đã duyệt (design/04 §4.1, NFR-015): m=65536 KiB (64 MB), t=3, p=4,
 * salt 16 bytes, hash 32 bytes. Encode/verify PHC format `$argon2id$v=19$m=…,t=…,p=…$salt$hash`
 * (Base64 không padding). So khớp constant-time; không dùng SHA/HMAC cho mật khẩu
 * (common README §3.3). Dummy hash sinh một lần khi load class — không phải password thật.
 */
@Component
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class Argon2PasswordHasher implements PasswordHasher {

    private static final int MEMORY_KIB = 65536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 4;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final Pattern PHC_PATTERN = Pattern.compile(
            "^\\$argon2id\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([A-Za-z0-9+/=]+)\\$([A-Za-z0-9+/=]+)$");

    /** Sinh đúng một lần (không phải mỗi lần fail login); salt/password ngẫu nhiên, không phải password thật. */
    private static final String DUMMY_HASH = generateDummyHash();

    private static String generateDummyHash() {
        SecureRandom random = new SecureRandom();
        byte[] dummyPassword = new byte[HASH_BYTES];
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(dummyPassword);
        random.nextBytes(salt);
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] digest = new byte[HASH_BYTES];
        generator.generateBytes(dummyPassword, digest);
        Arrays.fill(dummyPassword, (byte) 0);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$"
                + base64(salt) + "$" + base64(digest);
    }

    @Override
    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] digest = generate(password, params(salt), HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$"
                + base64(salt) + "$" + base64(digest);
    }

    @Override
    public boolean matches(String storedHash, char[] password) {
        if (storedHash == null || password == null) {
            throw new PasswordHashFormatException("Stored hash or password missing");
        }
        Matcher match = PHC_PATTERN.matcher(storedHash);
        if (!match.matches()) {
            throw new PasswordHashFormatException("Stored hash is not a supported Argon2id PHC string");
        }
        int version = Integer.parseInt(match.group(1));
        int memory = Integer.parseInt(match.group(2));
        int iterations = Integer.parseInt(match.group(3));
        int parallelism = Integer.parseInt(match.group(4));
        byte[] salt = decode(match.group(5));
        byte[] expected = decode(match.group(6));
        if (version != Argon2Parameters.ARGON2_VERSION_13) {
            throw new PasswordHashFormatException("Unsupported Argon2 version");
        }
        if (salt.length != SALT_BYTES || expected.length != HASH_BYTES || memory <= 0 || iterations <= 0
                || parallelism <= 0) {
            throw new PasswordHashFormatException("Stored hash parameters out of approved range");
        }
        byte[] actual = generate(password, params(salt), expected.length);
        boolean equal = MessageDigest.isEqual(actual, expected);
        Arrays.fill(actual, (byte) 0);
        return equal;
    }

    @Override
    public String dummyHash() {
        return DUMMY_HASH;
    }

    private static Argon2Parameters params(byte[] salt) {
        return new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();
    }

    private static byte[] generate(char[] password, Argon2Parameters parameters, int hashLength) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] digest = new byte[hashLength];
        generator.generateBytes(password, digest);
        return digest;
    }

    private static byte[] decode(String base64NoPadding) {
        try {
            int rem = base64NoPadding.length() % 4;
            String padded = rem == 0 ? base64NoPadding : base64NoPadding + "=".repeat(4 - rem);
            return Base64.getDecoder().decode(padded.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException malformed) {
            throw new PasswordHashFormatException("Stored hash base64 is malformed");
        }
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().withoutPadding().encodeToString(value);
    }
}
