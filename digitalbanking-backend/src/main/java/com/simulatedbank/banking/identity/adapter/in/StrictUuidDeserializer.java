package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reject UUID coercions and shortened groups accepted by UUID.fromString. */
public final class StrictUuidDeserializer extends JsonDeserializer<UUID> {
    private static final Pattern UUID_TEXT = Pattern.compile(IdentityRequestValidation.UUID_REGEX);

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(UUID.class, "deviceRef must be a UUID string");
        }
        String value = parser.getText();
        if (!UUID_TEXT.matcher(value).matches()) {
            return context.reportInputMismatch(UUID.class, "deviceRef must be a canonical UUID string");
        }
        return UUID.fromString(value);
    }
}
