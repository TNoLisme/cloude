package com.simulatedbank.banking.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CryptoPrimitivesTest {
    private static final byte[] KEY = HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    @Test
    void utf8AndEmptyRoundtripWithFreshIvAndCorrectBlobLayout() {
        PiiCryptoService crypto = new PiiCryptoService(KEY);
        Set<String> ivs = new HashSet<>();
        for (String value : new String[] {"", "0012345678", "Dữ liệu giả lập 🔒"}) {
            for (int i = 0; i < 50; i++) {
                byte[] encrypted = crypto.encrypt(value);
                assertThat(encrypted).hasSize(12 + value.getBytes(StandardCharsets.UTF_8).length + 16);
                assertThat(ivs.add(HexFormat.of().formatHex(Arrays.copyOf(encrypted, 12)))).isTrue();
                assertThat(crypto.decrypt(encrypted)).isEqualTo(value);
            }
        }
    }

    @Test
    void rejectsEveryTamperedByteWrongKeyTruncationAndExtensionWithoutProviderDetails() {
        PiiCryptoService crypto = new PiiCryptoService(KEY);
        byte[] blob = crypto.encrypt("synthetic-sensitive-plaintext");
        for (int i = 0; i < blob.length; i++) {
            byte[] tampered = blob.clone();
            tampered[i] ^= 1;
            assertRejected(crypto, tampered);
        }
        for (int length = 0; length < blob.length; length++) {
            assertRejected(crypto, Arrays.copyOf(blob, length));
        }
        assertRejected(crypto, Arrays.copyOf(blob, blob.length + 1));
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;
        assertRejected(new PiiCryptoService(otherKey), blob);
    }

    @Test
    void decryptsIndependentAes256GcmNistVector() {
        // NIST AES_GCM.pdf, GCM-AES256 Example #1, pp. 20–21: empty plaintext/AAD.
        byte[] key = HexFormat.of().parseHex(
                "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308");
        assertThat(new PiiCryptoService(key).decrypt(HexFormat.of().parseHex(
                "cafebabefacedbaddecaf888fd2caa16a5832e76aa132c1453eeda7e"))).isEmpty();
    }

    @Test
    void knownAnswersPreserveLeadingZerosWhitespaceUtf8AndOtpCallerBinding() {
        HmacService hmac = new HmacService(KEY);
        // Python stdlib hmac/hashlib oracles, saved in verification evidence.
        assertThat(hmac.hmacSha256Hex("00123"))
                .isEqualTo("676e45b13f0b910849cbc04891cf6de11be9e3ff71f042f7773c7e6470935bf5");
        assertThat(hmac.hmacSha256Hex("123"))
                .isEqualTo("e7c5538a4b15664ed667e05be3c25040fe6e433fbbc33c0cb5a85dfc09d9766c");
        assertThat(hmac.hmacSha256Hex(" 00123 "))
                .isEqualTo("38db950bd804d672637c80ee9d81e9a83ed0ca90abb85637ede1b8f6c0878888");
        assertThat(hmac.hmacSha256Hex("thử nghiệm"))
                .isEqualTo("093cd14b05fc7751df0c8b6e878421b2357e6a9ddbf2c31d4cdc407d34fd64c2");
        assertThat(hmac.hmacSha256Hex("challenge-a:123456"))
                .isEqualTo("5516948d2459071fb433e1cd80c4344dc37ad9737b154a45434a1d0dda09c306");
        assertThat(hmac.hmacSha256Hex("challenge-b:123456"))
                .isEqualTo("8f6046f1b3929543326b45f2ccd84c7379915937303ec99ab032a3bad70f9a68");
        assertThat(HmacService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(HmacService.sha256Hex("c4"))
                .isEqualTo("0012a3fa000c5dc26ee658c3c58e12cecd58d6455cec3d5621f0c787675b38aa");
    }

    @Test
    void constantTimePrimitiveAcceptsEqualAndRejectsDifferentAndDifferentLengths() {
        byte[] hash = HexFormat.of().parseHex(HmacService.sha256Hex("abc"));
        assertThat(HmacService.constantTimeEquals(hash, hash.clone())).isTrue();
        byte[] different = hash.clone();
        different[31] ^= 1;
        assertThat(HmacService.constantTimeEquals(hash, different)).isFalse();
        assertThat(HmacService.constantTimeEquals(hash, Arrays.copyOf(hash, 31))).isFalse();
        assertThat(HmacService.constantTimeEquals(new byte[0], new byte[0])).isTrue();
        assertThatThrownBy(() -> HmacService.constantTimeEquals(null, hash)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void primitivesRejectMissingInputAndInvalidKeyLengths() {
        assertThatThrownBy(() -> new PiiCryptoService(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacService(new byte[33])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacService(KEY).hmacSha256Hex(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HmacService.sha256Hex((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PiiCryptoService(KEY).encrypt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PiiCryptoService(KEY).decrypt(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertRejected(PiiCryptoService crypto, byte[] blob) {
        assertThatThrownBy(() -> crypto.decrypt(blob)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid encrypted PII").hasNoCause();
    }
}
