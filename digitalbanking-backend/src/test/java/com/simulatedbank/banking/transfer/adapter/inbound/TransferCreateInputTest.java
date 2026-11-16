package com.simulatedbank.banking.transfer.adapter.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput.InvalidRequestException;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferCreateInputTest {
    private static final List<String> KEY = List.of("unique_key-1");
    private static final String VALID = """
            {"sourceAccountId":"3001","destination":{"accountNumber":"0123456789"},
             "amount":"150000.00","description":"  memo  "}
            """;

    @Test
    void validRequestDefaultsCurrencyTrimsDescriptionAndPreservesLeadingZeros() {
        var input = parse(VALID, KEY);
        assertThat(input.sourceAccountId()).isEqualTo(3001);
        assertThat(input.destination().type()).isEqualTo(DestinationType.ACCOUNT_NUMBER);
        assertThat(input.destination().value()).isEqualTo("0123456789");
        assertThat(input.amount().value()).isEqualByComparingTo("150000.00");
        assertThat(input.amount().currency()).isEqualTo("VND");
        assertThat(input.description()).isEqualTo("memo");
        assertThat(input.idempotencyKey()).isEqualTo("unique_key-1");
        assertThat(input.toString()).doesNotContain("0123456789", "unique_key-1", "memo");
    }

    @Test
    void idDestinationAndNullDescriptionAreAccepted() {
        var input = parse("""
                {"sourceAccountId":"3001","destination":{"accountId":"3002"},
                 "amount":"0.01","currency":"VND","description":null}
                """, List.of("A"));
        assertThat(input.destination().type()).isEqualTo(DestinationType.ACCOUNT_ID);
        assertThat(input.destination().value()).isEqualTo("3002");
        assertThat(input.description()).isEmpty();
    }

    @Test
    void rejectsMissingInvalidOrMultipleIdempotencyHeaders() {
        for (List<String> keys : List.of(List.<String>of(), List.of(""), List.of("with space"),
                List.of("A", "B"), List.of("a".repeat(256)))) {
            assertInvalid(VALID, keys);
        }
        assertThatThrownBy(() -> parse(VALID, null)).isInstanceOf(InvalidRequestException.class);
        assertThat(parse(VALID, List.of("a".repeat(255))).idempotencyKey()).hasSize(255);
    }

    @Test
    void rejectsWrongShapeUnknownFieldsDuplicateKeysAndMultipleRoots() {
        for (String body : List.of("[]", "null", "{}", "{\"sourceAccountId\":\"1\",\"customerId\":\"2\"}",
                VALID.replace("\"amount\":", "\"actorId\":\"8\",\"amount\":"),
                VALID.replace("\"accountNumber\":\"0123456789\"", "\"accountNumber\":\"0123456789\",\"accountId\":\"2\""),
                VALID.replace("\"accountNumber\":\"0123456789\"", "\"accountNumber\":\"0123456789\",\"extra\":true"),
                VALID.replace("{\"accountNumber\":\"0123456789\"}", "{}"))) {
            assertInvalid(body, KEY);
        }
        assertMalformed(VALID.replace("\"amount\":\"150000.00\"", "\"amount\":\"150000.00\",\"amount\":\"1.00\""));
        assertMalformed(VALID.replace("\"accountNumber\":\"0123456789\"", "\"accountNumber\":\"0123456789\",\"accountNumber\":\"9876543210\""));
        assertMalformed(VALID + "{}");
        assertMalformed("{not-json}");
        assertMalformed("  ");
    }

    @Test
    void rejectsBadIdsDestinationAndTypesWithoutLeakingInput() {
        for (String body : List.of(VALID.replace("\"3001\"", "3001"), VALID.replace("\"3001\"", "\"03001\""),
                VALID.replace("\"3001\"", "\"9223372036854775808\""),
                VALID.replace("\"accountNumber\":\"0123456789\"", "\"accountId\":\"3001\""),
                VALID.replace("0123456789", "12345"), VALID.replace("0123456789", "1".repeat(21)),
                VALID.replace("0123456789", "12345x"), VALID.replace("\"0123456789\"", "null"))) {
            assertInvalid(body, KEY);
        }
        assertThatThrownBy(() -> parse(VALID.replace("0123456789", "private-secret"), KEY))
                .isInstanceOf(InvalidRequestException.class).hasMessage("Invalid transfer request")
                .hasMessageNotContaining("private-secret");
    }

    @Test
    void requiresExactDecimalStringPositiveAmountAndVnd() {
        for (String amount : List.of("0.00", "-1.00", "1", "1.0", "01.00", "1.000", "1E2", "100000000000000000.00")) {
            assertInvalid(VALID.replace("150000.00", amount), KEY);
        }
        assertInvalid(VALID.replace("\"150000.00\"", "150000.00"), KEY);
        assertInvalid(VALID.replace("\"amount\":", "\"currency\":\"USD\",\"amount\":"), KEY);
        assertInvalid(VALID.replace("\"amount\":", "\"currency\":null,\"amount\":"), KEY);
        assertThat(parse(VALID.replace("150000.00", "99999999999999999.99"), KEY).amount().value())
                .isEqualByComparingTo("99999999999999999.99");
    }

    @Test
    void rejectsDescriptionControlsInvalidUnicodeAndExcessLength() {
        assertInvalid(VALID.replace("  memo  ", "line\\nend"), KEY);
        assertInvalid(VALID.replace("  memo  ", "\\uD800"), KEY);
        assertInvalid(VALID.replace("  memo  ", "x".repeat(256)), KEY);
        assertThat(parse(VALID.replace("  memo  ", "😀".repeat(255)), KEY).description())
                .isEqualTo("😀".repeat(255));
    }

    @Test
    void sizeBoundIsByteBasedAndMalformedUtf8IsRejected() {
        byte[] valid = VALID.getBytes(StandardCharsets.UTF_8);
        assertThat(TransferCreateInput.parse(valid, KEY, valid.length).sourceAccountId()).isEqualTo(3001);
        assertThatThrownBy(() -> TransferCreateInput.parse(valid, KEY, valid.length - 1))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(error -> assertThat(((InvalidRequestException) error).kind()).isEqualTo(TransferCreateInput.Kind.TOO_LARGE));
        assertThatThrownBy(() -> TransferCreateInput.parse(new byte[]{(byte) 0xc3, (byte) 0x28}, KEY, 65536))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(error -> assertThat(((InvalidRequestException) error).kind()).isEqualTo(TransferCreateInput.Kind.MALFORMED));
        assertThatThrownBy(() -> TransferCreateInput.parse(valid, KEY, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static TransferCreateInput.Validated parse(String body, List<String> key) {
        return TransferCreateInput.parse(body == null ? null : body.getBytes(StandardCharsets.UTF_8), key, 65536);
    }

    private static void assertInvalid(String body, List<String> key) {
        assertThatThrownBy(() -> parse(body, key))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(error -> assertThat(((InvalidRequestException) error).kind()).isEqualTo(TransferCreateInput.Kind.VALIDATION));
    }

    private static void assertMalformed(String body) {
        assertThatThrownBy(() -> parse(body, KEY))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(error -> assertThat(((InvalidRequestException) error).kind()).isEqualTo(TransferCreateInput.Kind.MALFORMED));
    }
}
