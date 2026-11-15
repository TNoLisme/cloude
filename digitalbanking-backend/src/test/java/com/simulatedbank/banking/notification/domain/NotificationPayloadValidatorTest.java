package com.simulatedbank.banking.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPayloadValidatorTest {

    private final NotificationPayloadValidator validator = new NotificationPayloadValidator();

    @Test
    @DisplayName("Valid transfer notice with masked fields passes validation")
    void validTransferNoticePasses() {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN20261115A1B2C3D4",
                NotificationChannel.IN_APP,
                "TRANSFER_DEBIT_ALERT",
                Map.of(
                        "reference", "TXN20261115A1B2C3D4",
                        "amount", "500000",
                        "currency", "VND",
                        "maskedAccount", "**** **** 1234",
                        "balanceAfter", "4500000",
                        "outcome", "COMPLETED",
                        "timestamp", "2026-11-15T08:00:00Z"
                ),
                "corr-12345"
        );

        assertThatCode(() -> validator.validateTransferNotice(notice))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonVndNonFiniteAndInvalidTimeValues() {
        for (Map<String, Object> payload : java.util.List.of(
                Map.<String, Object>of("currency", "USD"), Map.<String, Object>of("amount", Double.NaN),
                Map.<String, Object>of("timestamp", "user@example.com"))) {
            var notice = new TransferNotice(1L, 2L, "TXN12345678", NotificationChannel.IN_APP,
                    "TRANSFER_ALERT", payload, "corr-1");
            assertThatThrownBy(() -> validator.validateTransferNotice(notice)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsSensitiveTopLevelTemplateCode() {
        var notice = new TransferNotice(1L, 2L, "TXN12345678", NotificationChannel.IN_APP,
                "user@example.com", Map.of(), "corr-1");
        assertThatThrownBy(() -> validator.validateTransferNotice(notice)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAccountLikeValuesInReferenceAndTemplateFields() {
        for (String key : java.util.List.of("reference", "templateCode")) {
            var notice = new TransferNotice(1L, 2L, "TXN12345678", NotificationChannel.IN_APP,
                    "TRANSFER_ALERT", Map.of(key, key.equals("reference") ? "123456789012" : "123456789012"), "corr-1");
            assertThatThrownBy(() -> validator.validateTransferNotice(notice)).isInstanceOf(IllegalArgumentException.class);
        }
    }


    @Test
    @DisplayName("Null customerId is rejected")
    void rejectsNullCustomerId() {
        TransferNotice notice = new TransferNotice(
                null,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1"),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    @DisplayName("Zero or negative customerId is rejected")
    void rejectsZeroOrNegativeCustomerId() {
        TransferNotice notice = new TransferNotice(
                0L,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1"),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    @DisplayName("Null or blank correlationId is rejected")
    void rejectsMissingCorrelationId() {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1"),
                "   "
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    @DisplayName("Null channel or templateCode is rejected")
    void rejectsMissingChannelOrTemplateCode() {
        TransferNotice notice1 = new TransferNotice(
                1001L, 5001L, "TXN1", null, "TEMPLATE1", Map.of("reference", "TXN1"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");

        TransferNotice notice2 = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.EMAIL, "", Map.of("reference", "TXN1"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateCode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789012", "0123456789", "9876543210123456", "1234 5678 9012", "1234-5678-9012", "9876 5432 1012 3456"})
    @DisplayName("Unmasked full account number in payload is rejected")
    void rejectsUnmaskedAccountNumber(String fullAccount) {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.SMS,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "maskedAccount", fullAccount),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+84912345678", "0912345678", "84901234567"})
    @DisplayName("Unmasked full phone number in payload is rejected")
    void rejectsUnmaskedPhone(String phone) {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.SMS,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "phone", phone),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer@example.com", "test.user@bank.com"})
    @DisplayName("Unmasked full email in payload is rejected")
    void rejectsUnmaskedEmail(String email) {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.EMAIL,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "email", email),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"otp", "password", "pin", "token", "secret", "session", "authorization"})
    @DisplayName("Sensitive credentials or tokens in payload keys or values are rejected")
    void rejectsSensitiveKeywordsInKeys(String sensitiveKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("reference", "TXN1");
        map.put(sensitiveKey, "123456");

        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.SMS,
                "TRANSFER_ALERT",
                map,
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sensitive");
    }

    @Test
    @DisplayName("Arbitrary unapproved free-text keys are rejected")
    void rejectsUnapprovedFreeTextKeys() {
        TransferNotice notice = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "freeTextNote", "Custom message from attacker"),
                "corr-1"
        );

        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disallowed payload key");
    }

    @Test
    @DisplayName("Nested structures (maps, lists) in payload values are rejected")
    void rejectsNestedStructuresInPayload() {
        TransferNotice noticeMap = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "maskedAccount", Map.of("nested", "value")),
                "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nested");

        TransferNotice noticeList = new TransferNotice(
                1001L,
                5001L,
                "TXN1",
                NotificationChannel.IN_APP,
                "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "maskedAccount", java.util.List.of("element")),
                "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nested");
    }

    @Test
    @DisplayName("Email embedded with prefix or suffix in payload values is rejected")
    void rejectsEmailWithPrefixOrSuffix() {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.EMAIL, "TRANSFER_ALERT",
                Map.of("reference", "Customer email: user@bank.com (verified)"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("Phone embedded with prefix or suffix in payload values is rejected")
    void rejectsPhoneWithPrefixOrSuffix() {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.SMS, "TRANSFER_ALERT",
                Map.of("reference", "Call us at +84 912 345 678 today"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789012*", "*123456789012", "1234 5678 9012*"})
    @DisplayName("Account with trailing/leading asterisk but 8+ consecutive unmasked digits is rejected")
    void rejectsImproperlyMaskedAccount(String account) {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "maskedAccount", account), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account number");
    }

    @Test
    @DisplayName("Invalid types for specific fields (amount, currency) are rejected")
    void rejectsInvalidFieldTypes() {
        // Non-numeric amount
        TransferNotice notice1 = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "amount", "not_a_number"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric");

        // Non-3-letter currency
        TransferNotice notice2 = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "currency", "VIETNAMESEDONG"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("Caller mutation of payload map after TransferNotice construction cannot alter enqueued notice")
    void callerMutationDoesNotAlterNotice() {
        Map<String, Object> mutableMap = new HashMap<>();
        mutableMap.put("reference", "TXN-ORIGINAL");
        mutableMap.put("amount", "100000");

        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN-ORIGINAL", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                mutableMap, "corr-1"
        );

        mutableMap.put("reference", "TXN-MUTATED");
        mutableMap.put("amount", "999999");

        assertThat(notice.payloadMasked()).containsEntry("reference", "TXN-ORIGINAL");
        assertThat(notice.payloadMasked()).containsEntry("amount", "100000");
    }

    @Test
    @DisplayName("Negative or zero amount is rejected")
    void rejectsNegativeOrZeroAmount() {
        TransferNotice noticeZero = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "amount", "0"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeZero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        TransferNotice noticeNegative = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "amount", "-50000"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeNegative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("Negative fee is rejected")
    void rejectsNegativeFee() {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "fee", "-1000"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fee");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "vnd", " VND ", "JPY"})
    @DisplayName("Non-VND, lowercase, or whitespace-padded currency is rejected")
    void rejectsInvalidCurrency(String currency) {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "currency", currency), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(notice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("Invalid outcome, channel, or transferId in payload is rejected")
    void rejectsInvalidOutcomeChannelOrTransferId() {
        TransferNotice noticeOutcome = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "outcome", "UNKNOWN_STATUS"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeOutcome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");

        TransferNotice noticeChannel = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "channel", "PIGEON_POST"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeChannel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");

        TransferNotice noticeTransferId = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "transferId", "invalid_id"), "corr-1"
        );
        assertThatThrownBy(() -> validator.validateTransferNotice(noticeTransferId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transferId");
    }

    @Test
    @DisplayName("Notice payloadMasked is unmodifiable and prevents direct modification")
    void payloadMaskedIsUnmodifiable() {
        TransferNotice notice = new TransferNotice(
                1001L, 5001L, "TXN1", NotificationChannel.IN_APP, "TRANSFER_ALERT",
                Map.of("reference", "TXN1", "amount", "100000"), "corr-1"
        );
        assertThatThrownBy(() -> notice.payloadMasked().put("amount", "200000"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
