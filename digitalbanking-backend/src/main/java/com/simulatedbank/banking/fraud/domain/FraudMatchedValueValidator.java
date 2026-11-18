package com.simulatedbank.banking.fraud.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates matched_value payloads to enforce allowlists and strictly prevent PII or credential leakage.
 */
public class FraudMatchedValueValidator {

    private static final Set<String> ALLOWED_MATCHED_KEYS = Set.of(
            "amount",
            "thresholdAmount",
            "currency",
            "count",
            "thresholdCount",
            "windowSeconds",
            "comparator",
            "ruleCode",
            "version"
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

    private static final Pattern UNMASKED_ACCOUNT = Pattern.compile("^\\d{8,19}$");
    private static final Pattern CONSECUTIVE_DIGITS = Pattern.compile("\\d{8,}");
    private static final Pattern UNMASKED_PHONE = Pattern.compile("(\\+?[0-9]{9,15})");
    private static final Pattern UNMASKED_EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Za-z]{3}$");

    public void validate(Map<String, Object> matchedValue) {
        if (matchedValue == null || matchedValue.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : matchedValue.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (!ALLOWED_MATCHED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Disallowed key in matched_value: " + key);
            }

            String lowerKey = key.toLowerCase(Locale.ROOT);
            for (String kw : SENSITIVE_KEYWORDS) {
                if (lowerKey.contains(kw)) {
                    throw new IllegalArgumentException("Sensitive keyword in matched_value key: " + key);
                }
            }

            if (value == null) {
                continue;
            }

            if (!(value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character)) {
                throw new IllegalArgumentException("Nested structures are not permitted in matched_value for key: " + key);
            }

            String strVal = value.toString();
            String lowerVal = strVal.toLowerCase(Locale.ROOT);

            for (String kw : SENSITIVE_KEYWORDS) {
                if (lowerVal.contains(kw)) {
                    throw new IllegalArgumentException("Sensitive credential detected in matched_value for key: " + key);
                }
            }

            if (lowerKey.equals("amount") || lowerKey.equals("thresholdamount")) {
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
                    throw new IllegalArgumentException("Field '" + key + "' must be a valid numeric amount");
                }
                if (num.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Field '" + key + "' must be non-negative");
                }
            } else if (lowerKey.equals("count") || lowerKey.equals("thresholdcount")
                    || lowerKey.equals("windowseconds") || lowerKey.equals("version")) {
                if (value instanceof Double || value instanceof Float) {
                    throw new IllegalArgumentException("Field '" + key + "' must be an integer");
                }
                long longVal;
                if (value instanceof Number n) {
                    longVal = n.longValue();
                } else if (value instanceof String s) {
                    try {
                        longVal = Long.parseLong(s.trim());
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Field '" + key + "' must be a valid integer");
                    }
                } else {
                    throw new IllegalArgumentException("Field '" + key + "' must be an integer");
                }
                if (longVal < 0 || longVal > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Field '" + key + "' must be a valid non-negative integer");
                }
                int intVal = (int) longVal;
                if (lowerKey.equals("windowseconds") && intVal <= 0) {
                    throw new IllegalArgumentException("Field 'windowSeconds' must be strictly positive");
                }
                if (lowerKey.equals("version") && intVal < 1) {
                    throw new IllegalArgumentException("Field 'version' must be at least 1");
                }
            } else if (lowerKey.equals("currency")) {
                if (!(value instanceof String) || !"VND".equals(strVal.trim())) {
                    throw new IllegalArgumentException("Field 'currency' must be VND");
                }
            } else if (lowerKey.equals("comparator")) {
                if (!strVal.equals("GREATER_THAN") && !strVal.equals("GREATER_OR_EQUAL")) {
                    throw new IllegalArgumentException("Invalid comparator in matched_value");
                }
            }

            String digits = strVal.replaceAll("[\\s-]", "");
            if (lowerKey.contains("account")) {
                if (UNMASKED_ACCOUNT.matcher(digits).matches() || CONSECUTIVE_DIGITS.matcher(digits).find()) {
                    throw new IllegalArgumentException("Unmasked account number detected in matched_value for key: " + key);
                }
            } else if (!lowerKey.equals("amount") && !lowerKey.equals("thresholdamount")
                    && !lowerKey.equals("count") && !lowerKey.equals("thresholdcount")
                    && !lowerKey.equals("windowseconds") && !lowerKey.equals("version")) {
                if (UNMASKED_ACCOUNT.matcher(digits).matches() || CONSECUTIVE_DIGITS.matcher(digits).find()) {
                    throw new IllegalArgumentException("Unmasked account number detected in matched_value for key: " + key);
                }
            }

            if (UNMASKED_EMAIL.matcher(strVal).find()) {
                throw new IllegalArgumentException("Unmasked email detected in matched_value for key: " + key);
            }

            if (!lowerKey.equals("amount") && !lowerKey.equals("thresholdamount")
                    && !lowerKey.equals("count") && !lowerKey.equals("thresholdcount") && !lowerKey.equals("windowseconds")) {
                if (UNMASKED_PHONE.matcher(digits).find() || Pattern.compile("(\\+84|0)[0-9]{8,10}").matcher(digits).find()) {
                    throw new IllegalArgumentException("Unmasked phone detected in matched_value for key: " + key);
                }
            }
        }
    }

    public boolean isSafe(Map<String, Object> matchedValue) {
        try {
            validate(matchedValue);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
