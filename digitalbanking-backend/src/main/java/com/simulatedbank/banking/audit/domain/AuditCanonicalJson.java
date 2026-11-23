package com.simulatedbank.banking.audit.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Audit value validation and Jackson serialization; Common owns RFC 8785 canonicalization. */
public final class AuditCanonicalJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JcsCanonicalizer JCS = new JcsCanonicalizer();
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private AuditCanonicalJson() { }

    public static String canonicalize(Object value) {
        validateValues(value, 0);
        try {
            return new String(JCS.canonicalize(JSON.writeValueAsString(value)), StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Value cannot be canonicalized as RFC 8785 JSON", exception);
        }
    }

    public static String serialize(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid audit JSON", exception);
        }
    }

    private static void validateValues(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("JSON nesting exceeds the audit limit");
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String string) {
            validateUnicode(string);
            return;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return;
        if (value instanceof Long number) {
            if (number < -MAX_SAFE_INTEGER || number > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("Integer exceeds RFC 8785 safe range");
            }
            return;
        }
        if (value instanceof Double number && Double.isFinite(number)) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Object key must be a string");
                validateUnicode(key);
                validateValues(entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) validateValues(item, depth + 1);
            return;
        }
        throw new IllegalArgumentException("Unsupported or non-finite JSON value");
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("Unpaired surrogate is not valid JSON");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Unpaired surrogate is not valid JSON");
            }
        }
    }
}
