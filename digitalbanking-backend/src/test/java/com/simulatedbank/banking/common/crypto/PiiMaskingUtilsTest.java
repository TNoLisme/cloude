package com.simulatedbank.banking.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PiiMaskingUtilsTest {
    @Test
    void sourceFormatsPreserveOnlyPermittedParts() {
        assertThat(PiiMaskingUtils.maskAccountLast4("0012")).isEqualTo("**** **** 0012");
        assertThat(PiiMaskingUtils.maskNationalIdLast4("1234")).isEqualTo("********1234");
        assertThat(PiiMaskingUtils.maskPhone("+84900000123")).isEqualTo("********123");
        assertThat(PiiMaskingUtils.maskPhone("0123")).isEqualTo("********123");
        assertThat(PiiMaskingUtils.maskEmail("n.synthetic@example.com")).isEqualTo("n***@example.com");
        assertThat(PiiMaskingUtils.maskEmail("n@example.com")).isEqualTo("n***@example.com");
        assertThat(PiiMaskingUtils.maskIp("203.0.113.42")).isEqualTo("203.0.113.***");
        assertThat(PiiMaskingUtils.maskIp("2001:db8::1234")).isNull();
    }

    @Test
    void missingValuesStayAbsent() {
        assertThat(PiiMaskingUtils.maskAccountLast4(null)).isNull();
        assertThat(PiiMaskingUtils.maskNationalIdLast4(null)).isNull();
        assertThat(PiiMaskingUtils.maskPhone(null)).isNull();
        assertThat(PiiMaskingUtils.maskEmail(null)).isNull();
        assertThat(PiiMaskingUtils.maskIp(null)).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidInputNeverFallsBackToPlaintext(Function<String, String> mask, String input) {
        assertThatThrownBy(() -> mask.apply(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid masking input").hasNoCause();
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskAccountLast4, ""),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskAccountLast4, "12345"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskNationalIdLast4, "abcd"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskPhone, "123"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskPhone, "synthetic-phone"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskPhone, "0123\n"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskEmail, "invalid-email"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskEmail, "a@b@example.com"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskEmail, "a@example.com\nsecret"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskIp, "256.0.1.2"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskIp, "203.0.113.42\n"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskIp, "203.0.113"),
                Arguments.of((Function<String, String>) PiiMaskingUtils::maskIp, "example.com"));
    }
}
