package com.simulatedbank.banking.account.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.application.AccountNumberProtection.ProtectedAccountNumber;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit contract của crypto/blind-index/masking Account (BE-ACC-03).
 *
 * <p>Bảo vệ các bất biến ảnh hưởng người dùng cuối: ciphertext không chứa plaintext, blind index
 * deterministic và keyed (không phải SHA-256 unkeyed), normalize strict `^[0-9]{6,20}$`, masking
 * đúng OpenAPI `**** **** <last4>`, lỗi crypto không rò dữ liệu.</p>
 */
class CommonAccountNumberProtectionAdapterTest {

    private static final String ACCOUNT_NUMBER = "0012345678";

    private CommonAccountNumberProtectionAdapter protection;

    @BeforeEach
    void setUp() {
        byte[] aesKey = new byte[32];
        byte[] hmacKey = new byte[32];
        Arrays.fill(aesKey, (byte) 0x11);
        Arrays.fill(hmacKey, (byte) 0x22);
        protection = new CommonAccountNumberProtectionAdapter(
                new PiiCryptoService(aesKey), new HmacService(hmacKey));
    }

    @Test
    void encryptsWithAesGcmAndDecryptsOnlyWithMatchingKeyAndTag() {
        ProtectedAccountNumber protectedAccount = protection.protect(ACCOUNT_NUMBER);

        assertThat(protectedAccount.encrypted()).hasSizeGreaterThan(28);
        assertThat(new String(protectedAccount.encrypted(), StandardCharsets.UTF_8))
                .doesNotContain(ACCOUNT_NUMBER);
        assertThat(protection.decrypt(protectedAccount.encrypted())).isEqualTo(ACCOUNT_NUMBER);
    }

    @Test
    void usesFreshIvPerEncryptionSoEqualNumbersProduceDifferentCiphertext() {
        ProtectedAccountNumber first = protection.protect(ACCOUNT_NUMBER);
        ProtectedAccountNumber second = protection.protect(ACCOUNT_NUMBER);

        assertThat(first.encrypted()).isNotEqualTo(second.encrypted());
        assertThat(first.blindIndex()).isEqualTo(second.blindIndex());
    }

    @Test
    void blindIndexIsDeterministicKeyedHmacAndNeverUnkeyedSha256() {
        byte[] hmacKey = new byte[32];
        Arrays.fill(hmacKey, (byte) 0x22);

        String blindIndex = protection.blindIndex(ACCOUNT_NUMBER);

        assertThat(blindIndex).matches("^[0-9a-f]{64}$");
        assertThat(blindIndex).isEqualTo(new HmacService(hmacKey).hmacSha256Hex(ACCOUNT_NUMBER));
        assertThat(blindIndex).isNotEqualTo(HmacService.sha256Hex(ACCOUNT_NUMBER));
        assertThat(protection.blindIndex("0000000001")).isNotEqualTo(blindIndex);
    }

    @Test
    void normalizationIsStrictAndNeverStripsSpacesOrLeadingZeros() {
        assertThat(protection.blindIndex("0012345678"))
                .isNotEqualTo(protection.blindIndex("12345678"));

        for (String invalid : new String[] {null, "", "12345", "123456789012345678901", " 123456",
                "123456 ", "+123456", "12345a", "１２３４５６"}) {
            assertThatThrownBy(() -> protection.blindIndex(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(ACCOUNT_NUMBER);
        }
    }

    @Test
    void protectedNumberCarriesConsistentHashAndLast4AndDefensiveCiphertextCopy() {
        ProtectedAccountNumber protectedAccount = protection.protect(ACCOUNT_NUMBER);

        assertThat(protectedAccount.accountNumberLast4()).isEqualTo("5678");
        assertThat(protectedAccount.blindIndex()).isEqualTo(protection.blindIndex(ACCOUNT_NUMBER));
        assertThat(protectedAccount.toString()).isEqualTo("ProtectedAccountNumber[redacted]");

        byte[] leaked = protectedAccount.encrypted();
        leaked[0] ^= 0x7f;
        assertThat(protection.decrypt(protectedAccount.encrypted())).isEqualTo(ACCOUNT_NUMBER);
    }

    @Test
    void corruptCiphertextFailsClosedWithoutEchoingData() {
        byte[] corrupt = protection.protect(ACCOUNT_NUMBER).encrypted();
        corrupt[corrupt.length - 1] ^= 0x01;

        assertThatThrownBy(() -> protection.decrypt(corrupt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ACCOUNT_NUMBER);
        assertThatThrownBy(() -> protection.decrypt(new byte[12]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ACCOUNT_NUMBER);
        assertThatThrownBy(() -> protection.decrypt(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void masksWithTheApprovedWireFormatAndRejectsInvalidLast4() {
        assertThat(protection.mask("5678")).isEqualTo("**** **** 5678");
        assertThatThrownBy(() -> protection.mask("567")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protection.mask("567a")).isInstanceOf(IllegalArgumentException.class);
        assertThat(protection.toString()).isEqualTo("CommonAccountNumberProtectionAdapter[redacted]");
    }
}
