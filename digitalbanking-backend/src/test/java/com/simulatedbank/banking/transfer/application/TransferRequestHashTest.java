package com.simulatedbank.banking.transfer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransferRequestHashTest {
    private final TransferRequestHash hasher = new TransferRequestHash(new ObjectMapper(), new JcsCanonicalizer());
    private final Destination accountNumber = new Destination(DestinationType.ACCOUNT_NUMBER, "0123456789");

    private TransferAmount amount(String value) {
        return new TransferAmount(new BigDecimal(value), "VND");
    }

    @Test
    void matchesPublishedPayloadAndIndependentSha256Vector() {
        assertEquals("b03c53146d9feb3f9b22374ba6bd91d98171e3061f3f5ce4b01fb482e0f5839a",
                hasher.hash(2001, 3001, accountNumber, amount("150000.00"), "Chuyển tiền tháng 11"));
    }

    @Test
    void normalizesAmountAndDescriptionButPreservesDestinationTypeAndValue() {
        String base = hasher.hash(2001, 3001, accountNumber, amount("00150.0"), null);
        assertEquals(base, hasher.hash(2001, 3001, accountNumber, amount("150.00"), "  "));
        assertEquals(hasher.hash(2001, 3001, accountNumber, amount("150"), " text "),
                hasher.hash(2001, 3001, accountNumber, amount("150.00"), "text"));
        assertNotEquals(base, hasher.hash(2002, 3001, accountNumber, amount("150"), null));
        assertNotEquals(base, hasher.hash(2001, 3002, accountNumber, amount("150"), null));
        assertNotEquals(base, hasher.hash(2001, 3001,
                new Destination(DestinationType.ACCOUNT_ID, "123456789"), amount("150"), null));
        assertNotEquals(base, hasher.hash(2001, 3001,
                new Destination(DestinationType.ACCOUNT_NUMBER, "123456789"), amount("150"), null));
        assertNotEquals(hasher.hash(2001, 3001, new Destination(DestinationType.ACCOUNT_ID, "123456"), amount("150"), null),
                hasher.hash(2001, 3001, new Destination(DestinationType.ACCOUNT_NUMBER, "123456"), amount("150"), null));
        assertNotEquals(base, hasher.hash(2001, 3001, accountNumber, amount("150.01"), null));
    }

    @Test
    void rejectsInvalidInputsWithoutEmbeddingDestinationInErrors() {
        assertThrows(IllegalArgumentException.class, () -> new Destination(DestinationType.ACCOUNT_NUMBER, "12345"));
        assertThrows(IllegalArgumentException.class, () -> new Destination(DestinationType.ACCOUNT_ID, "9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> new Destination(DestinationType.ACCOUNT_ID, "01"));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(0, 1, accountNumber, amount("1"), null));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(1, 1, accountNumber, amount("1"), "\n"));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(1, 1, accountNumber, amount("1"), "x".repeat(256)));
        assertEquals("Destination[redacted]", accountNumber.toString());
    }

    @Test
    void rejectsInvalidUnicodeWithoutLeakingRequestData() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> hasher.hash(2001, 3001, accountNumber, amount("1"), "private\uD800"));
        assertEquals("Invalid JCS input", error.getMessage());
    }
}
