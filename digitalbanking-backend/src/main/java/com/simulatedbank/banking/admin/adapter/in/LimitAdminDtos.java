package com.simulatedbank.banking.admin.adapter.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

final class LimitAdminDtos {
    private LimitAdminDtos() { }

    public record ChangeRequest(@JsonDeserialize(using = StrictString.class) String customerId,
            @JsonDeserialize(using = StrictString.class) String accountId,
            @JsonDeserialize(using = StrictString.class) String perTransactionLimit,
            @JsonDeserialize(using = StrictString.class) String dailyLimit,
            @JsonDeserialize(using = StrictString.class) String currency,
            @JsonDeserialize(using = StrictInstant.class) Instant effectiveFrom,
            @JsonDeserialize(using = StrictString.class) @Size(max = 500) String reason) { }

    public enum Decision { APPROVE, REJECT }
    public record DecisionRequest(@NotNull @JsonDeserialize(using = StrictDecision.class) Decision decision,
            @JsonDeserialize(using = StrictString.class) @Size(max = 500) String decisionNote) { }

    public static final class StrictString extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }

    public static final class StrictDecision extends JsonDeserializer<Decision> {
        @Override public Decision deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) return (Decision) context.handleUnexpectedToken(Decision.class, parser);
            try { return Decision.valueOf(parser.getText()); }
            catch (IllegalArgumentException invalid) {
                return (Decision) context.handleWeirdStringValue(Decision.class, parser.getText(), "Invalid decision");
            }
        }
    }

    public static final class StrictInstant extends JsonDeserializer<Instant> {
        @Override public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            try { return Instant.parse(parser.getText()); }
            catch (java.time.format.DateTimeParseException invalid) {
                return (Instant) context.handleWeirdStringValue(Instant.class, parser.getText(), "Invalid timestamp");
            }
        }
    }

    public record ActorResponse(String userId, String displayName) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalResponse(String approvalId, String requestType, String targetType, String targetId, String status,
            ActorResponse maker, ActorResponse checker, Instant createdAt, Instant decidedAt, Instant appliedAt,
            String decisionNote, String correlationId, Map<String, Object> payload, Map<String, Object> result) { }
}
