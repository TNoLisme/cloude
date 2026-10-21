package com.bank.simulator.transfer.application;

import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferPolicyTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void ut01CanonicalAmountParsesToBigDecimal() {
        assertThat(TransferPolicy.parseAmount("50000")).isEqualTo(new BigDecimal("50000"));
        assertThat(TransferPolicy.parseAmount("2000")).isEqualTo(new BigDecimal("2000"));
        assertThat(TransferPolicy.parseAmount("10000000")).isEqualTo(new BigDecimal("10000000"));
    }

    @Test
    void ut02NonCanonicalAmountsRejectedWithAmountInvalid() {
        for (String value : new String[]{"50000.5", "0", "-100", "+100", " 100", "1e5"}) {
            assertThatThrownBy(() -> TransferPolicy.parseAmount(value))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException api = (ApiException) error;
                        assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.code()).isEqualTo("AMOUNT_INVALID");
                    });
        }
    }

    @Test
    void ut03OutOfRangeAmountsRejectedWithAmountInvalid() {
        for (String value : new String[]{"1999", "10000001"}) {
            assertThatThrownBy(() -> TransferPolicy.parseAmount(value))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("AMOUNT_INVALID"));
        }
    }

    @Test
    void requiresOtpOnlyAboveFiveMillion() {
        assertThat(TransferPolicy.requiresOtp(new BigDecimal("5000000"))).isFalse();
        assertThat(TransferPolicy.requiresOtp(new BigDecimal("5000001"))).isTrue();
    }

    @Test
    void ut05NullAndEmptyMemoProduceSamePayloadHash() {
        BigDecimal amount = new BigDecimal("50000");
        assertThat(TransferPolicy.payloadHash(SOURCE, DESTINATION, amount, "VND", null))
                .isEqualTo(TransferPolicy.payloadHash(SOURCE, DESTINATION, amount, "VND", ""));
    }

    @Test
    void ut06ChangingAnyFieldChangesPayloadHash() {
        BigDecimal amount = new BigDecimal("50000");
        String base = TransferPolicy.payloadHash(SOURCE, DESTINATION, amount, "VND", "memo");
        assertThat(TransferPolicy.payloadHash(DESTINATION, DESTINATION, amount, "VND", "memo")).isNotEqualTo(base);
        assertThat(TransferPolicy.payloadHash(SOURCE, SOURCE, amount, "VND", "memo")).isNotEqualTo(base);
        assertThat(TransferPolicy.payloadHash(SOURCE, DESTINATION, new BigDecimal("50001"), "VND", "memo")).isNotEqualTo(base);
        assertThat(TransferPolicy.payloadHash(SOURCE, DESTINATION, amount, "USD", "memo")).isNotEqualTo(base);
        assertThat(TransferPolicy.payloadHash(SOURCE, DESTINATION, amount, "VND", "other")).isNotEqualTo(base);
    }

    @Test
    void payloadHashIsHexSha256WithoutPin() {
        String hash = TransferPolicy.payloadHash(SOURCE, DESTINATION, new BigDecimal("50000"), "VND", null);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
