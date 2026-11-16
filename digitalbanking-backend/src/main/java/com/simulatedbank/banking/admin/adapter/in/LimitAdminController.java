package com.simulatedbank.banking.admin.adapter.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.simulatedbank.banking.admin.application.LimitApprovalBinding;
import com.simulatedbank.banking.admin.application.LimitApprovalService;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/limits")
public class LimitAdminController {
    private final LimitApprovalService service;

    public LimitAdminController(LimitApprovalService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApprovalResponse> propose(Authentication actor, @Valid @RequestBody ChangeRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) String challenge) {
        var change = new LimitApprovalBinding.Change(LimitApprovalBinding.id(request.customerId()),
                request.accountId() == null ? null : LimitApprovalBinding.id(request.accountId()),
                LimitApprovalBinding.money(request.perTransactionLimit()), LimitApprovalBinding.money(request.dailyLimit()),
                request.currency() == null ? "VND" : request.currency(), request.effectiveFrom(), request.reason());
        var result = service.propose(actor, change, challenge(challenge), RequestContextHolder.current().orElseThrow());
        return ResponseEntity.created(URI.create("/api/v1/admin/approvals/" + result.approval().approvalId()))
                .body(ApprovalResponse.from(result, false));
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponse decide(Authentication actor, @PathVariable String id, @Valid @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) String challenge) {
        return ApprovalResponse.from(service.decide(actor, LimitApprovalBinding.id(id), request.decision().name(),
                request.decisionNote(), challenge(challenge), RequestContextHolder.current().orElseThrow()), true);
    }

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

    private static UUID challenge(String value) {
        if (value == null) return null;
        try {
            var parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException invalid) { throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR); }
    }

    public record ActorResponse(String userId, String displayName) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalResponse(String approvalId, String requestType, String targetType, String targetId, String status,
            ActorResponse maker, ActorResponse checker, Instant createdAt, Instant decidedAt, Instant appliedAt,
            String decisionNote, String correlationId, Map<String, Object> payload, Map<String, Object> result) {
        static ApprovalResponse from(LimitApprovalService.Result facts, boolean detail) {
            var approval = facts.approval();
            var config = facts.configuration();
            return new ApprovalResponse(Long.toString(approval.approvalId()), approval.requestType(), approval.targetType(),
                    approval.targetId(), approval.status(), new ActorResponse(Long.toString(facts.maker().userId()), facts.maker().displayName()),
                    facts.checker() == null ? null : new ActorResponse(Long.toString(facts.checker().userId()), facts.checker().displayName()),
                    approval.createdAt(), approval.decidedAt(), approval.appliedAt(), approval.decisionNote(), approval.correlationId(),
                    detail ? approval.payload() : null, config == null || !"APPLIED".equals(approval.status()) ? null : Map.of("limitId", config.limitId().toString(),
                            "version", config.version(), "effectiveFrom", config.effectiveFrom().toString()));
        }
    }
}
