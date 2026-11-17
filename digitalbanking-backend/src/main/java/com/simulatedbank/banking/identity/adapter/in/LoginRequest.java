package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** DTO `API-AUTH-01` (07 §8.1.1): username 1–64, password 1–128 (không trim), device fields optional. */
public record LoginRequest(
        @NotNull @Size(min = 1, max = 64)
        @JsonDeserialize(using = StrictStringDeserializer.class) String username,
        @NotNull @Size(min = 1, max = 128)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @JsonDeserialize(using = StrictUuidDeserializer.class) UUID deviceRef,
        @Size(min = 1, max = 100)
        @JsonDeserialize(using = StrictStringDeserializer.class) String deviceName,
        @Size(min = 1, max = 512)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String deviceFingerprint) {
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
