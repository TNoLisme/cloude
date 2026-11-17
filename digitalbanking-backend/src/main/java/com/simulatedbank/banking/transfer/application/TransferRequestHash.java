package com.simulatedbank.banking.transfer.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.util.Map;
import java.util.Objects;

/** Canonical request hash only; authorization and idempotency state belong to the caller. */
public final class TransferRequestHash {
    private final ObjectMapper mapper;
    private final JcsCanonicalizer canonicalizer;

    public TransferRequestHash(ObjectMapper mapper, JcsCanonicalizer canonicalizer) {
        this.mapper = Objects.requireNonNull(mapper);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
    }

    /** Customer ID must come from the authenticated server-side context. */
    public String hash(long customerId, long sourceAccountId, Destination destination,
                       TransferAmount amount, String description) {
        if (customerId <= 0 || sourceAccountId <= 0) {
            throw new IllegalArgumentException("Invalid customer or source ID");
        }
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amount, "amount");
        String normalizedDescription = description == null ? "" : description.trim();
        if (description != null && (description.codePointCount(0, description.length()) > 255
                || description.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid description");
        }
        Map<String, Object> payload = Map.of(
                "customerId", Long.toString(customerId),
                "sourceAccountId", Long.toString(sourceAccountId),
                "destination", Map.of("type", destination.type().name(), "value", destination.value()),
                "amount", amount.value().toPlainString(),
                "currency", amount.currency(),
                "description", normalizedDescription);
        try {
            byte[] canonical = canonicalizer.canonicalize(mapper.writeValueAsString(payload));
            return HmacService.sha256Hex(canonical);
        } catch (JsonProcessingException ex) {
            // Do not propagate a serialization message that could include request data.
            throw new IllegalStateException("Cannot hash transfer request");
        }
    }

    public enum DestinationType { ACCOUNT_ID, ACCOUNT_NUMBER }

    public record Destination(DestinationType type, String value) {
        public Destination {
            Objects.requireNonNull(type, "destination type");
            if (value == null) {
                throw new IllegalArgumentException("Invalid destination");
            }
            if (type == DestinationType.ACCOUNT_NUMBER) {
                if (!value.matches("[0-9]{6,20}")) {
                    throw new IllegalArgumentException("Invalid destination");
                }
            } else {
                if (!value.matches("[1-9][0-9]{0,18}")) {
                    throw new IllegalArgumentException("Invalid destination");
                }
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid destination");
                }
            }
        }

        @Override
        public String toString() {
            return "Destination[redacted]";
        }
    }
}
