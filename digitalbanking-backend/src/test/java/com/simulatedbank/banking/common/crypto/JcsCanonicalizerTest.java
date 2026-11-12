package com.simulatedbank.banking.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JcsCanonicalizerTest {
    private final JcsCanonicalizer jcs = new JcsCanonicalizer();

    @ParameterizedTest
    @CsvSource({
        "0000000000000000,0", "8000000000000000,0",
        "0000000000000001,5e-324", "8000000000000001,-5e-324",
        "7fefffffffffffff,1.7976931348623157e+308", "ffefffffffffffff,-1.7976931348623157e+308",
        "4340000000000000,9007199254740992", "c340000000000000,-9007199254740992",
        "4430000000000000,295147905179352830000",
        "44b52d02c7e14af5,9.999999999999997e+22", "44b52d02c7e14af6,1e+23",
        "44b52d02c7e14af7,1.0000000000000001e+23",
        "444b1ae4d6e2ef4e,999999999999999700000", "444b1ae4d6e2ef4f,999999999999999900000",
        "444b1ae4d6e2ef50,1e+21", "3eb0c6f7a0b5ed8c,9.999999999999997e-7",
        "3eb0c6f7a0b5ed8d,0.000001", "41b3de4355555553,333333333.3333332",
        "41b3de4355555554,333333333.33333325", "41b3de4355555555,333333333.3333333",
        "41b3de4355555556,333333333.3333334", "41b3de4355555557,333333333.33333343",
        "becbf647612f3696,-0.0000033333333333333333", "43143ff3c1cb0959,1424953923781206.2"
    })
    void rfc8785AppendixBNumberVectors(String bits, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));
        assertCanonical("[" + Double.toString(value) + "]", "[" + expected + "]");
    }

    @Test
    void rfcUtf16KeyOrderIncludesSupplementaryCodePoints() {
        // RFC 8785 §3.2.3; UTF-16 ordering differs from UTF-8 and code point ordering.
        assertCanonical("{\"€\":1,\"\\r\":2,\"דּ\":3,\"1\":4,\"😀\":5,\"\\u0080\":6,\"ö\":7}",
                "{\"\\r\":2,\"1\":4,\"\u0080\":6,\"ö\":7,\"€\":1,\"😀\":5,\"דּ\":3}");
    }

    @Test
    void numbersEscapingNullArrayOrderAndNestedKeysFollowJcs() {
        assertCanonical("{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,1e-27],"
                        + "\"array\":[null,true,false,{\"z\":1,\"a\":2}],\"s\":\"\\u000f\\n\\b\\t\\f\\r\\\"\\\\/€\"}",
                "{\"array\":[null,true,false,{\"a\":2,\"z\":1}],"
                        + "\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"s\":\"\\u000f\\n\\b\\t\\f\\r\\\"\\\\/€\"}");
        assertCanonical("[\"e\\u0301\",\"é\"]", "[\"é\",\"é\"]");
        assertCanonical("null", "null");
        assertCanonical(" true ", "true");
        assertCanonical(" 4.50 ", "4.5");
        assertCanonical("\"\\u0061\"", "\"a\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{} {}", "1 2", "NaN", "Infinity", "[1e400]", "[01]", "[+1]",
        "{\"a\":1,\"a\":2}", "{\"a\":null,\"\\u0061\":2}", "{\"a\":{\"b\":1,\"b\":2}}",
        "[\"\\ud800\"]", "{\"\\udfff\":0}", "[\"\\ud800x\"]", "[1,]", "/*secret*/{}"})
    void invalidDomainAndDuplicateKeysAreRejectedBeforeInformationIsLost(String json) {
        assertThatThrownBy(() -> jcs.canonicalize(json)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid JCS input").hasNoCause();
    }

    @Test
    void callerOwnedTransferAndAuditPayloadsMatchIndependentHashes() throws IOException {
        String transfer = resource("transfer.json");
        assertThat(HmacService.sha256Hex(jcs.canonicalize(transfer)))
                .isEqualTo("b03c53146d9feb3f9b22374ba6bd91d98171e3061f3f5ce4b01fb482e0f5839a");
        String audit = resource("audit.json");
        assertThat(new String(jcs.canonicalize(audit), StandardCharsets.UTF_8)).isEqualTo(audit);
        String prev = "9c1f5b2e7a4d8c6f0a3b5d7e9f1c2a4b6d8e0f2a4c6e8b0d2f4a6c8e0b2d4f6a";
        assertThat(HmacService.sha256Hex(new String(jcs.canonicalize(audit), StandardCharsets.UTF_8) + "|" + prev))
                .isEqualTo("1c221546084975b20527994c4c1fd739741c63f7b184e2b1f1f6d203ec07405e");
    }

    private void assertCanonical(String input, String expected) {
        assertThat(jcs.canonicalize(input)).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String resource(String name) throws IOException {
        try (var input = JcsCanonicalizerTest.class.getResourceAsStream("/crypto/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
    }
}
