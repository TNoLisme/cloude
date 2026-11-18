package com.simulatedbank.banking.audit.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditCanonicalJsonTest {

    @Test
    void canonicalizesObjectKeysByUtf16AndEscapesJsonStrings() {
        var value = new LinkedHashMap<String, Object>();
        value.put("z", "line\n\"quoted\"");
        value.put("a", java.util.Arrays.asList(true, null, 3L));

        assertThat(AuditCanonicalJson.canonicalize(value))
                .isEqualTo("{\"a\":[true,null,3],\"z\":\"line\\n\\\"quoted\\\"\"}");
    }

    @Test
    void rejectsNumbersOutsideSafeIntegerRange() {
        assertThatThrownBy(() -> AuditCanonicalJson.canonicalize(9_007_199_254_740_992L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> AuditCanonicalJson.canonicalize(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesEcmaNumberRenderingRatherThanJacksonDefaults() {
        assertThat(AuditCanonicalJson.canonicalize(List.of(1e30, 4.50, 2e-3, 1e-27)))
                .isEqualTo("[1e+30,4.5,0.002,1e-27]");
    }

    @Test
    void sortsSupplementaryKeysByUtf16NotCodePoint() {
        assertThat(AuditCanonicalJson.canonicalize(Map.of("", "private", "😀", "emoji")))
                .isEqualTo("{\"😀\":\"emoji\",\"\":\"private\"}");
    }

    @Test
    void handlesNullRootAndAllJsonControlCharacters() {
        assertThat(AuditCanonicalJson.canonicalize(null)).isEqualTo("null");
        assertThat(AuditCanonicalJson.canonicalize(Map.of("value", "\u0000\b\f")))
                .isEqualTo("{\"value\":\"\\u0000\\b\\f\"}");
    }

    @Test
    void rejectsUnpairedSurrogate() {
        assertThatThrownBy(() -> AuditCanonicalJson.canonicalize(Map.of("value", "\ud800")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
