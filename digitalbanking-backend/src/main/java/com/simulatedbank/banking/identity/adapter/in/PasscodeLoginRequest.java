package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Arrays;

/** API-AUTH-02: metadata identifies a device but never proves possession. */
public record PasscodeLoginRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = PasscodeDeserializer.class) char[] passcode,
        @JsonDeserialize(using = StrictStringDeserializer.class) String deviceRef,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = StrictStringDeserializer.class) String deviceFingerprint) {

    void erasePasscode() {
        if (passcode != null) {
            Arrays.fill(passcode, '\0');
        }
    }

    @Override
    public String toString() {
        return "PasscodeLoginRequest[redacted]";
    }

    public static final class PasscodeDeserializer extends JsonDeserializer<char[]> {
        @Override
        public char[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(char[].class, "passcode must be a string");
            }
            int offset = parser.getTextOffset();
            return Arrays.copyOfRange(parser.getTextCharacters(), offset, offset + parser.getTextLength());
        }
    }
}
