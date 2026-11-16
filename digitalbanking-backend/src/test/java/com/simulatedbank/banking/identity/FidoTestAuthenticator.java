package com.simulatedbank.banking.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier.Assertion;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Credential;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only authenticator: generates fresh real keys and signs the WebAuthn assertion bytes. */
public final class FidoTestAuthenticator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final KeyPair keys;
    private final Map<Integer, Object> cose;
    private final String signatureAlgorithm;
    private final String credentialId;
    private final String userHandle;

    private FidoTestAuthenticator(KeyPair keys, Map<Integer, Object> cose, String signatureAlgorithm) {
        this.keys = keys;
        this.cose = cose;
        this.signatureAlgorithm = signatureAlgorithm;
        this.credentialId = randomBase64(32);
        this.userHandle = randomBase64(32);
    }

    public static FidoTestAuthenticator create() {
        return create("ES256");
    }

    /** All non-SHA1 algorithms supported by the adapter on Java 21. */
    public static FidoTestAuthenticator create(String algorithm) {
        try {
            Map<Integer, Object> cose = new LinkedHashMap<>();
            if (algorithm.startsWith("ES")) {
                int bits = Integer.parseInt(algorithm.substring(2));
                String curve = switch (bits) {
                    case 256 -> "secp256r1";
                    case 384 -> "secp384r1";
                    case 512 -> "secp521r1";
                    default -> throw new IllegalArgumentException(algorithm);
                };
                var generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec(curve));
                KeyPair pair = generator.generateKeyPair();
                ECPublicKey key = (ECPublicKey) pair.getPublic();
                int width = (key.getParams().getCurve().getField().getFieldSize() + 7) / 8;
                cose.put(1, 2);
                cose.put(3, bits == 256 ? -7 : bits == 384 ? -35 : -36);
                cose.put(-1, bits == 256 ? 1 : bits == 384 ? 2 : 3);
                cose.put(-2, unsigned(key.getW().getAffineX(), width));
                cose.put(-3, unsigned(key.getW().getAffineY(), width));
                return new FidoTestAuthenticator(pair, cose, "SHA" + bits + "withECDSA");
            }
            if (algorithm.startsWith("RS")) {
                int bits = Integer.parseInt(algorithm.substring(2));
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                RSAPublicKey key = (RSAPublicKey) pair.getPublic();
                cose.put(1, 3);
                cose.put(3, bits == 256 ? -257 : bits == 384 ? -258 : -259);
                cose.put(-1, unsigned(key.getModulus(), 256));
                cose.put(-2, unsigned(key.getPublicExponent(), (key.getPublicExponent().bitLength() + 7) / 8));
                return new FidoTestAuthenticator(pair, cose, "SHA" + bits + "withRSA");
            }
            boolean ed448 = "Ed448".equals(algorithm);
            if (!ed448 && !"EdDSA".equals(algorithm) && !"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException(algorithm);
            }
            String name = ed448 ? "Ed448" : "Ed25519";
            KeyPair pair = KeyPairGenerator.getInstance(name).generateKeyPair();
            byte[] encoded = pair.getPublic().getEncoded();
            int width = ed448 ? 57 : 32;
            cose.put(1, 1);
            cose.put(3, ed448 ? -53 : "EdDSA".equals(algorithm) ? -8 : -19);
            cose.put(-1, ed448 ? 7 : 6);
            cose.put(-2, Arrays.copyOfRange(encoded, encoded.length - width, encoded.length));
            return new FidoTestAuthenticator(pair, cose, name);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Test authenticator key generation failed", exception);
        }
    }

    public String credentialId() {
        return credentialId;
    }

    public String publicKeyCose() {
        return encodeCose(cose);
    }

    /** Keeps the key material but changes its declared algorithm for fail-secure tests. */
    public String publicKeyCose(int algorithm) {
        Map<Integer, Object> changed = new LinkedHashMap<>(cose);
        changed.put(3, algorithm);
        return encodeCose(changed);
    }

    public String userHandle() {
        return userHandle;
    }

    public Credential credential(long deviceId, long userId, String deviceRef, long storedCounter) {
        return new Credential(deviceId, userId, deviceRef, credentialId, publicKeyCose(), userHandle,
                storedCounter, true, "ACTIVE");
    }

    public Assertion sign(String nonce, String rpId, String origin, long counter) {
        return sign(nonce, rpId, origin, counter, 0x05, "webauthn.get", false);
    }

    public Assertion sign(String nonce, String rpId, String origin, long counter,
                          int flags, String type, boolean crossOrigin) {
        try {
            return signClientData(JSON.writeValueAsString(Map.of(
                    "type", type, "challenge", nonce, "origin", origin, "crossOrigin", crossOrigin)),
                    rpId, counter, flags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Assertion signClientData(String clientDataJson, String rpId, long counter, int flags) {
        if (counter < 0 || counter > 0xffff_ffffL) {
            throw new IllegalArgumentException("Counter is unsigned 32-bit");
        }
        try {
            byte[] clientData = clientDataJson.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] authenticatorData = ByteBuffer.allocate(37)
                    .put(sha256.digest(rpId.getBytes(StandardCharsets.UTF_8)))
                    .put((byte) flags).putInt((int) counter).array();
            Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initSign(keys.getPrivate());
            signature.update(authenticatorData);
            signature.update(sha256.digest(clientData));
            return new Assertion(credentialId, base64(clientData), base64(authenticatorData),
                    base64(signature.sign()), userHandle);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Test authenticator signing failed", exception);
        }
    }

    private static String randomBase64(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return base64(bytes);
    }

    private static byte[] unsigned(BigInteger value, int width) {
        byte[] encoded = value.toByteArray();
        byte[] result = new byte[width];
        int count = Math.min(encoded.length, width);
        System.arraycopy(encoded, encoded.length - count, result, width - count, count);
        return result;
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Minimal test-only CBOR encoder for integer-keyed COSE maps; no production verification logic.
    private static String encodeCose(Map<Integer, Object> map) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeCborHeader(output, 5, map.size());
        map.forEach((key, value) -> {
            writeCborInteger(output, key);
            if (value instanceof Integer integer) {
                writeCborInteger(output, integer);
            } else {
                byte[] bytes = (byte[]) value;
                writeCborHeader(output, 2, bytes.length);
                output.writeBytes(bytes);
            }
        });
        return base64(output.toByteArray());
    }

    private static void writeCborInteger(ByteArrayOutputStream output, int value) {
        writeCborHeader(output, value >= 0 ? 0 : 1, value >= 0 ? value : -1L - value);
    }

    private static void writeCborHeader(ByteArrayOutputStream output, int major, long value) {
        if (value < 24) {
            output.write((major << 5) | (int) value);
        } else if (value <= 255) {
            output.write((major << 5) | 24);
            output.write((int) value);
        } else if (value <= 65535) {
            output.write((major << 5) | 25);
            output.write((int) (value >>> 8));
            output.write((int) value);
        } else {
            output.write((major << 5) | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                output.write((int) (value >>> shift));
            }
        }
    }
}
