package com.simulatedbank.banking.common.crypto;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

@Component
public final class JcsCanonicalizer {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** Accept raw JSON so duplicate keys cannot be lost through a tree/Map conversion. */
    public byte[] canonicalize(String json) {
        Objects.requireNonNull(json, "JSON is required");
        try (JsonParser parser = JSON.createParser(json)) {
            JsonToken token;
            int roots = 0;
            int depth = 0;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0) {
                    roots++;
                }
                if (token.isStructStart()) {
                    depth++;
                } else if (token.isStructEnd()) {
                    depth--;
                }
                if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                    validateUnicode(parser.getText());
                } else if (token.isNumeric() && !Double.isFinite(parser.getDoubleValue())) {
                    throw new IllegalArgumentException("Invalid JCS input");
                }
            }
            if (roots != 1) {
                throw new IllegalArgumentException("Invalid JCS input");
            }
            // The library accepts containers only. A singleton array also supports scalar roots.
            byte[] canonical = new JsonCanonicalizer("[" + json + "]").getEncodedUTF8();
            return Arrays.copyOfRange(canonical, 1, canonical.length - 1);
        } catch (IOException | IllegalArgumentException ex) {
            // Parser/provider messages may include the original payload.
            throw new IllegalArgumentException("Invalid JCS input");
        }
    }

    private static void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Invalid JCS input");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("Invalid JCS input");
            }
        }
    }
}
