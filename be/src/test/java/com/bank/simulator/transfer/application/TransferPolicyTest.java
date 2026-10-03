package com.bank.simulator.transfer.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferPolicyTest {
    @Test
    void requiresOtpOnlyAboveFiveMillion() {
        assertThat(TransferPolicy.requiresOtp(5_000_000L)).isFalse();
        assertThat(TransferPolicy.requiresOtp(5_000_001L)).isTrue();
    }

    @Test
    void rejectsNonCanonicalAmounts() {
        for (String value : new String[]{"1999", "02000", "2000.0", "+2000", "10000001"}) {
            assertThatThrownBy(() -> TransferPolicy.parseAmount(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TransferPolicy.parseAmount("2000")).isEqualTo(2000L);
    }
}
