package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.LimitApprovalBinding;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LimitApprovalBindingTest {
    private final LimitApprovalBinding bindings = new LimitApprovalBinding(new JcsCanonicalizer(), new ObjectMapper());

    @Test void normalizedReasonAndMoneyHaveStableBindingButActorAndPayloadChangesDoNot() {
        var first = new LimitApprovalBinding.Change(11, null, new BigDecimal("1.0"), new BigDecimal("2.00"), "VND", null, " reason ");
        var same = new LimitApprovalBinding.Change(11, null, new BigDecimal("1.00"), new BigDecimal("2.00"), "VND", null, "reason");
        assertThat(bindings.create(1, first)).isEqualTo(bindings.create(1, same));
        assertThat(bindings.create(2, first)).isNotEqualTo(bindings.create(1, first));
        assertThat(bindings.create(1, first).approvalId()).isNull();
        var withoutReason = new LimitApprovalBinding.Change(11, null, new BigDecimal("1.00"), new BigDecimal("2.00"), "VND", null, null);
        var blankReason = new LimitApprovalBinding.Change(11, null, new BigDecimal("1.00"), new BigDecimal("2.00"), "VND", null, " ");
        assertThat(bindings.create(1, withoutReason)).isEqualTo(bindings.create(1, blankReason));
        assertThat(bindings.decision(2, 1, "APPROVE", null, Map.of("value", "1")))
                .isNotEqualTo(bindings.decision(2, 2, "APPROVE", null, Map.of("value", "1")));
        assertThat(bindings.decision(2, 1, "APPROVE", null, Map.of("value", "1")))
                .isNotEqualTo(bindings.decision(2, 1, "REJECT", null, Map.of("value", "1")));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "01", "9223372036854775808", "11.0"})
    void invalidIdsRejectWithoutOverflowLeaks(String id) {
        assertThatThrownBy(() -> LimitApprovalBinding.id(id)).isInstanceOf(BankingBusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"otp=123456", "a@example.com", "Bearer secret", "hello\nworld", "0901234567"})
    void obviousSecretAndPiiOrControlCharactersAreNotAccepted(String note) {
        assertThatThrownBy(() -> LimitApprovalBinding.note(note)).isInstanceOf(BankingBusinessException.class);
    }
}
