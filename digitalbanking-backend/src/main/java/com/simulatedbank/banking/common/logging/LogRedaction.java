package com.simulatedbank.banking.common.logging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centralized log redaction (BE-SEC-17, BE-OBS-16; design/04 §7.1 Level 4 "cấm tuyệt đối").
 * Một nguồn duy nhất cho mọi module: không log cục bộ từng service tự lọc riêng lẻ.
 *
 * <p>Nguyên tắc fail-safe: nghi ngờ là secret → redact; giá trị không khớp sample đã duyệt
 * cũng được redact. Không bao giờ thay đổi dữ liệu nghiệp vụ — chỉ áp dụng cho text/log/metadata.</p>
 */
public final class LogRedaction {

    public static final String REDACTED = "[REDACTED]";

    private LogRedaction() { }

    /** Exact normalized keys (non-alnum stripped, lowercase) that are always secrets. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "passphrase", "passcode", "pin", "pincode",
            "otp", "otpcode", "otphash", "onetimecode", "onetimepassword", "verificationcode",
            "verifycode", "mfa", "mfasecret", "mfacode", "totp", "totpsecret",
            "recoverycode", "recoverycodes",
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken",
            "sessiontoken", "sessionid", "session", "jsessionid",
            "csrftoken", "csrf", "xcsrftoken", "xsrf",
            "authorization", "cookie", "setcookie",
            "secret", "clientsecret", "apikey", "privatekey", "encryptionkey", "hmackey",
            "credential", "credentials", "username", "passwordhash", "passwordhashhex");

    /** Substring rules for composed names (`password_hash`, `refreshToken`, `mfaSecret`...). */
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "password", "passwd", "passcode", "otp", "token", "secret", "cookie",
            "authorization", "csrf", "apikey", "recoverycode", "mfasecret", "credential");

    /** `key=value`, `key: value` and JSON `"key":"value"` assignments of sensitive keys. */
    private static final java.util.regex.Pattern SENSITIVE_ASSIGNMENT = java.util.regex.Pattern.compile(
            "(?i)((?:\\\"|')?(?:password|passwd|pwd|passphrase|passcode|otp|one[-_ ]?time[-_ ]?(?:code|password)"
                    + "|verification[-_ ]?code|verify[-_ ]?code|mfa|mfasecret|totp|recovery[-_ ]?code"
                    + "|secret|clientsecret|api[-_ ]?key|privatekey|token|access[-_ ]?token|refresh[-_ ]?token"
                    + "|id[-_ ]?token|bearer[-_ ]?token|session[-_ ]?token|session[-_ ]?id|csrf"
                    + "|x[-_ ]?csrf[-_ ]?token|authorization|cookie|set[-_ ]?cookie|credential|username"
                    + "|pin)(?:\\\"|')?\\s*[:=]\\s*)"
                    + "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&)}\\]]+)");

    private static final java.util.regex.Pattern BEARER_TOKEN = java.util.regex.Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");

    /** Redacts sensitive assignments inside free text (exception/detail/message scrubbing). */
    public static String redactText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String scrubbed = BEARER_TOKEN.matcher(raw).replaceAll("$1" + REDACTED);
        return SENSITIVE_ASSIGNMENT.matcher(scrubbed).replaceAll("$1" + REDACTED);
    }

    /** Deep redaction of structured log/audit metadata; non-sensitive values are preserved. */
    public static Map<String, Object> redact(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return input == null ? null : Map.of();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            output.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue()));
        }
        return output;
    }

    private static Object redactValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), redactValue(String.valueOf(entry.getKey()), entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object element : list) {
                copy.add(element instanceof Map<?, ?> nested
                        ? redactValue(key, nested)
                        : element);
            }
            return copy;
        }
        return value;
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty()) {
            return false;
        }
        return SENSITIVE_KEYS.contains(normalized)
                || SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }
}
