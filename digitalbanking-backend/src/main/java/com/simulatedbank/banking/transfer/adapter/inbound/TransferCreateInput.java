package com.simulatedbank.banking.transfer.adapter.inbound;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Candidate HTTP input parser, not a registered endpoint. The HTTP adapter must
 * enforce request-size limits, authentication, authorization and PII policy.
 * Validation of shape alone cannot establish that descriptions or keys contain no PII.
 */
public final class TransferCreateInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("sourceAccountId", "destination", "amount", "currency", "description");
    private static final String ID = "[1-9][0-9]{0,18}";
    private static final String MONEY = "(0|[1-9][0-9]{0,16})\\.[0-9]{2}";
    private static final String KEY = "[A-Za-z0-9._:~\\-]{1,255}";

    private TransferCreateInput() { }

    /** Header values must contain exactly one Idempotency-Key, never a client actor/customer. */
    public static Validated parse(byte[] body, List<String> idempotencyHeaders, int maxBodyBytes) {
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("Invalid request size configuration");
        if (idempotencyHeaders == null || idempotencyHeaders.size() != 1
                || idempotencyHeaders.getFirst() == null || !idempotencyHeaders.getFirst().matches(KEY)) {
            throw invalid();
        }
        if (body == null) throw malformed();
        if (body.length > maxBodyBytes) throw tooLarge();
        JsonNode root;
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            root = JSON.readTree(json);
        } catch (JsonProcessingException | CharacterCodingException ex) {
            // Parser messages may include raw request values.
            throw malformed();
        }
        if (root == null || root.isMissingNode()) throw malformed();
        if (!root.isObject()) throw invalid();
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!FIELDS.contains(fields.next())) throw invalid();
        }
        long source = parseId(string(root.get("sourceAccountId")));
        Destination destination = destination(root.get("destination"));
        if (destination.type() == DestinationType.ACCOUNT_ID && source == parseId(destination.value())) throw invalid();
        String amount = string(root.get("amount"));
        if (!amount.matches(MONEY)) throw invalid();
        TransferAmount validatedAmount;
        try {
            validatedAmount = new TransferAmount(new BigDecimal(amount), currency(root));
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        String description = description(root.get("description"));
        return new Validated(source, destination, validatedAmount, description, idempotencyHeaders.getFirst());
    }

    private static Destination destination(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 1) throw invalid();
        if (node.has("accountId")) {
            String value = string(node.get("accountId"));
            parseId(value);
            return new Destination(DestinationType.ACCOUNT_ID, value);
        }
        if (node.has("accountNumber")) {
            String value = string(node.get("accountNumber"));
            if (!value.matches("[0-9]{6,20}")) throw invalid();
            return new Destination(DestinationType.ACCOUNT_NUMBER, value);
        }
        throw invalid();
    }

    private static long parseId(String value) {
        if (!value.matches(ID)) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static String currency(JsonNode root) {
        return root.has("currency") ? string(root.get("currency")) : "VND";
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static String description(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String value = string(node);
        if (value.codePointCount(0, value.length()) > 255 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
        return value.trim();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(Kind.VALIDATION); }
    private static InvalidRequestException malformed() { return new InvalidRequestException(Kind.MALFORMED); }
    private static InvalidRequestException tooLarge() { return new InvalidRequestException(Kind.TOO_LARGE); }

    public enum Kind { VALIDATION, MALFORMED, TOO_LARGE }

    /** Immutable and constructed only by the parser; do not log values or serialize this as a response. */
    public static final class Validated {
        private final long sourceAccountId;
        private final Destination destination;
        private final TransferAmount amount;
        private final String description;
        private final String idempotencyKey;

        private Validated(long sourceAccountId, Destination destination, TransferAmount amount,
                          String description, String idempotencyKey) {
            this.sourceAccountId = sourceAccountId;
            this.destination = destination;
            this.amount = amount;
            this.description = description;
            this.idempotencyKey = idempotencyKey;
        }
        public long sourceAccountId() { return sourceAccountId; }
        public Destination destination() { return destination; }
        public TransferAmount amount() { return amount; }
        public String description() { return description; }
        public String idempotencyKey() { return idempotencyKey; }
        @Override public String toString() { return "TransferCreateInput[redacted]"; }
    }

    /** Classification helps the future controller choose the existing shared Problem code. */
    public static final class InvalidRequestException extends IllegalArgumentException {
        private final Kind kind;
        private InvalidRequestException(Kind kind) {
            super(switch (kind) {
                case MALFORMED -> "Malformed transfer request";
                case VALIDATION -> "Invalid transfer request";
                case TOO_LARGE -> "Transfer request too large";
            });
            this.kind = kind;
        }
        public Kind kind() { return kind; }
    }
}
