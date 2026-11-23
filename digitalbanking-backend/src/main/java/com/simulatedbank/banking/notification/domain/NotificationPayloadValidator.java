package com.simulatedbank.banking.notification.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates notification notice payloads to ensure compliance with field allowlists
 * and strict prevention of unmasked PII, credentials, or arbitrary free-text.
 */
public class NotificationPayloadValidator {

    private static final Set<String> ALLOWED_TRANSFER_PAYLOAD_KEYS = Set.of(
            "reference",
            "amount",
            "currency",
            "maskedAccount",
            "balanceAfter",
            "outcome",
            "timestamp",
            "channel",
            "templateCode",
            "transferId",
            "fee"
    );

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "otp",
            "password",
            "pin",
            "token",
            "secret",
            "session",
            "authorization"
    );

    private static final Pattern UNMASKED_ACCOUNT_PATTERN = Pattern.compile("^\\d{8,19}$");
    private static final Pattern UNMASKED_PHONE_PATTERN = Pattern.compile("(\\+?[0-9]{9,15})");
    private static final Pattern UNMASKED_EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CONSECUTIVE_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
    private static final Pattern TRANSFER_REFERENCE = Pattern.compile("TXN[0-9]{8}[A-Z0-9]{8,20}");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Za-z]{3}$");

    public void validateTransferNotice(TransferNotice notice) {
        if (notice == null) {
            throw new IllegalArgumentException("TransferNotice cannot be null");
        }
        if (notice.customerId() == null || notice.customerId() <= 0) {
            throw new IllegalArgumentException("Valid customerId (> 0) is required");
        }
        if (notice.channel() == null) {
            throw new IllegalArgumentException("Notification channel cannot be null");
        }
        if (notice.templateCode() == null || notice.templateCode().isBlank()) {
            throw new IllegalArgumentException("templateCode cannot be null or blank");
        }
        if (UNMASKED_EMAIL_PATTERN.matcher(notice.templateCode()).find()
                || CONSECUTIVE_DIGITS_PATTERN.matcher(notice.templateCode()).find()) {
            throw new IllegalArgumentException("templateCode contains unsafe data");
        }
        if (notice.correlationId() == null || notice.correlationId().isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be null or blank");
        }

        validatePayload(notice.payloadMasked());
    }

    private void validatePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            String lowerKey = key.toLowerCase(Locale.ROOT);

            // 1. Check sensitive keywords in key
            for (String sensitive : SENSITIVE_KEYWORDS) {
                if (lowerKey.contains(sensitive)) {
                    throw new IllegalArgumentException("Sensitive key '" + key + "' is not permitted in notification payload");
                }
            }

            // 2. Key allowlist check
            if (!ALLOWED_TRANSFER_PAYLOAD_KEYS.contains(key)) {
                throw new IllegalArgumentException("Disallowed payload key: '" + key + "'. Only approved fields are permitted");
            }

            if (value == null) {
                continue;
            }

            // 3. Reject nested structures (maps, collections, complex objects)
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character)) {
                throw new IllegalArgumentException("Nested structures are not permitted in notification payload for key: " + key);
            }

            String strVal = value.toString();
            String lowerVal = strVal.toLowerCase(Locale.ROOT);

            // A canonical server-issued reference may contain digit runs and reserved words by chance.
            if (key.equals("reference") && value instanceof String && TRANSFER_REFERENCE.matcher(strVal).matches()) {
                continue;
            }

            // 4. Check sensitive keywords in string value
            for (String sensitive : SENSITIVE_KEYWORDS) {
                if (lowerVal.contains(sensitive)) {
                    throw new IllegalArgumentException("Sensitive value containing '" + sensitive + "' is not permitted");
                }
            }

            // 5. Per-field type validation
            if (lowerKey.equals("amount") || lowerKey.equals("balanceafter") || lowerKey.equals("fee")) {
                if (value instanceof Double d && (!Double.isFinite(d) || d.isNaN())) {
                    throw new IllegalArgumentException("Field '" + key + "' must be finite");
                }
                if (value instanceof Float f && (!Float.isFinite(f) || f.isNaN())) {
                    throw new IllegalArgumentException("Field '" + key + "' must be finite");
                }
                if (strVal.equalsIgnoreCase("NaN") || strVal.contains("Infinity")) {
                    throw new IllegalArgumentException("Field '" + key + "' must be finite");
                }
                java.math.BigDecimal num;
                try {
                    num = new java.math.BigDecimal(strVal.trim());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Field '" + key + "' must be a valid numeric value");
                }
                if (lowerKey.equals("amount") && num.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Field 'amount' must be positive (> 0)");
                }
                if (lowerKey.equals("fee") && num.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Field 'fee' must be non-negative (>= 0)");
                }
            } else if (lowerKey.equals("currency")) {
                if (!(value instanceof String) || !"VND".equals(value)) {
                    throw new IllegalArgumentException("Field 'currency' must be VND");
                }
            } else if (lowerKey.equals("timestamp")) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid notification timestamp");
                try { java.time.Instant.parse((String) value); }
                catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("Invalid notification timestamp"); }
            } else if (lowerKey.equals("outcome")) {
                if (!(value instanceof String s) || !Set.of("COMPLETED", "FAILED", "PENDING", "REJECTED").contains(s.trim())) {
                    throw new IllegalArgumentException("Field 'outcome' must be one of: COMPLETED, FAILED, PENDING, REJECTED");
                }
            } else if (lowerKey.equals("channel")) {
                if (!(value instanceof String s) || !Set.of("IN_APP", "SMS", "EMAIL").contains(s.trim())) {
                    throw new IllegalArgumentException("Field 'channel' must be a valid channel (IN_APP, SMS, EMAIL)");
                }
            } else if (lowerKey.equals("transferid")) {
                if (!(value instanceof Number)) {
                    try {
                        Long.parseLong(strVal.trim());
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Field 'transferId' must be a valid numeric ID");
                    }
                }
            } else if (lowerKey.equals("reference")) {
                if (!(value instanceof String s) || s.isBlank() || s.length() > 64) {
                    throw new IllegalArgumentException("Field 'reference' must be a non-blank string of max length 64");
                }
            } else if (lowerKey.equals("templatecode")) {
                if (!(value instanceof String s) || s.isBlank() || s.length() > 32) {
                    throw new IllegalArgumentException("Field 'templateCode' must be a non-blank string of max length 32");
                }
            }

            // 6. Reject unmasked or improperly masked account number
            String accountDigits = strVal.replaceAll("[\\s-]", "");
            if (lowerKey.contains("account")) {
                if (!strVal.contains("*")) {
                    if (UNMASKED_ACCOUNT_PATTERN.matcher(accountDigits).matches()) {
                        throw new IllegalArgumentException("Unmasked account number detected in payload for key: " + key);
                    }
                } else {
                    if (CONSECUTIVE_DIGITS_PATTERN.matcher(accountDigits).find()) {
                        throw new IllegalArgumentException("Improperly masked account number detected for key: " + key);
                    }
                }
            }

            // 7. Reject unmasked phone number
            String phoneDigits = strVal.replaceAll("[\\s-]", "");
            if (!lowerKey.equals("amount") && !lowerKey.equals("balanceafter") && !lowerKey.equals("fee")
                    && !lowerKey.equals("transferid") && !lowerKey.equals("timestamp")) {
                if (UNMASKED_PHONE_PATTERN.matcher(phoneDigits).find() || Pattern.compile("(\\+84|0)[0-9]{8,10}").matcher(phoneDigits).find()) {
                    throw new IllegalArgumentException("Unmasked phone number detected in payload for key: " + key);
                }
            }

            // 8. Reject unmasked email
            if (UNMASKED_EMAIL_PATTERN.matcher(strVal).find()) {
                throw new IllegalArgumentException("Unmasked email detected in payload for key: " + key);
            }
        }
    }
}
