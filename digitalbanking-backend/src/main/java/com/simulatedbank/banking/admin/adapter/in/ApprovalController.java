package com.simulatedbank.banking.admin.adapter.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simulatedbank.banking.admin.application.ApprovalService;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin")
public class ApprovalController {
    private final ApprovalService approvals;
    private final ObjectMapper objectMapper;

    public ApprovalController(ApprovalService approvals, ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/limits")
    public ResponseEntity<ApprovalService.ApprovalSummary> createLimit(Authentication authentication,
            @Valid @RequestBody LimitChangeRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) UUID challengeId) {
        ApprovalService.Actor actor = actor(authentication);
        JsonNode payload = limitPayload(request);
        String targetType = request.accountId() == null ? "CUSTOMER" : "ACCOUNT";
        String targetId = request.accountId() == null ? request.customerId() : request.accountId();
        JsonNode binding = approvals.binding("CREATE", ApprovalService.RequestType.LIMIT_CHANGE.name(), targetId, payload);
        approvals.requireStepUp(actor, ApprovalService.RequestType.LIMIT_CHANGE, binding, challengeId);
        ApprovalService.ApprovalSummary created = approvals.create(actor, ApprovalService.RequestType.LIMIT_CHANGE,
                targetType, targetId, payload, challengeId);
        return ResponseEntity.created(URI.create("/api/v1/admin/approvals/" + created.approvalId())).body(created);
    }

    @PostMapping("/roles")
    public ResponseEntity<ApprovalService.ApprovalSummary> createRole(Authentication authentication,
            @Valid @RequestBody RoleChangeRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) UUID challengeId) {
        ApprovalService.Actor actor = actor(authentication);
        JsonNode payload = rolePayload(request);
        JsonNode binding = approvals.binding("CREATE", ApprovalService.RequestType.ROLE_CHANGE.name(),
                request.targetUserId(), payload);
        approvals.requireStepUp(actor, ApprovalService.RequestType.ROLE_CHANGE, binding, challengeId);
        ApprovalService.ApprovalSummary created = approvals.create(actor, ApprovalService.RequestType.ROLE_CHANGE,
                "APP_USER", request.targetUserId(), payload, challengeId);
        return ResponseEntity.created(URI.create("/api/v1/admin/approvals/" + created.approvalId())).body(created);
    }

    @PostMapping("/limits/{id}/approve")
    public ApprovalService.ApprovalDetail decideLimit(Authentication authentication, @PathVariable long id,
            @Valid @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) UUID challengeId) {
        return decide(authentication, id, ApprovalService.RequestType.LIMIT_CHANGE, request, challengeId);
    }

    @PostMapping("/roles/{id}/approve")
    public ApprovalService.ApprovalDetail decideRole(Authentication authentication, @PathVariable long id,
            @Valid @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) UUID challengeId) {
        return decide(authentication, id, ApprovalService.RequestType.ROLE_CHANGE, request, challengeId);
    }

    @GetMapping("/approvals")
    public ApprovalService.ApprovalPage list(Authentication authentication,
            @RequestParam(required = false) String requestType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long makerId,
            @RequestParam(required = false) Long checkerId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return approvals.list(actor(authentication), new ApprovalService.ApprovalFilter(requestType, status, makerId,
                checkerId, from, to, page, size, sort));
    }

    @GetMapping("/approvals/{approvalId}")
    public ApprovalService.ApprovalDetail get(Authentication authentication, @PathVariable long approvalId) {
        return approvals.get(actor(authentication), approvalId);
    }

    private ApprovalService.ApprovalDetail decide(Authentication authentication, long id,
            ApprovalService.RequestType type, DecisionRequest request, UUID challengeId) {
        ApprovalService.Actor actor = actor(authentication);
        JsonNode payload = objectMapper.valueToTree(Map.of("decision", request.decision(),
                "decisionNote", request.decisionNote() == null ? "" : request.decisionNote()));
        JsonNode binding = approvals.binding("DECIDE_" + request.decision(), type.name(), Long.toString(id), payload);
        approvals.requireStepUp(actor, type, binding, challengeId);
        return approvals.decide(id, type, actor, ApprovalService.Decision.valueOf(request.decision()),
                request.decisionNote(), challengeId);
    }

    private JsonNode limitPayload(LimitChangeRequest request) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("customerId", request.customerId())
                .put("perTransactionLimit", request.perTransactionLimit().toPlainString())
                .put("dailyLimit", request.dailyLimit().toPlainString())
                .put("currency", request.currency() == null ? "VND" : request.currency());
        if (request.accountId() == null) payload.putNull("accountId"); else payload.put("accountId", request.accountId());
        if (request.effectiveFrom() != null) payload.put("effectiveFrom", request.effectiveFrom().toString());
        if (request.reason() != null) payload.put("reason", request.reason());
        return payload;
    }

    private JsonNode rolePayload(RoleChangeRequest request) {
        ObjectNode payload = objectMapper.createObjectNode().put("operation", request.operation())
                .put("targetUserId", request.targetUserId());
        if (request.roleCode() != null) payload.put("roleCode", request.roleCode());
        if (request.privilegeCode() != null) payload.put("privilegeCode", request.privilegeCode());
        if (request.reason() != null) payload.put("reason", request.reason());
        return payload;
    }

    private static ApprovalService.Actor actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session))
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        return ApprovalService.Actor.from(session);
    }

    public record LimitChangeRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String customerId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String accountId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal perTransactionLimit,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal dailyLimit,
            @Pattern(regexp = "^VND$") String currency,
            Instant effectiveFrom,
            @Size(max = 500) String reason) { }

    public record RoleChangeRequest(
            @NotBlank @Pattern(regexp = "^(ASSIGN_ROLE|REVOKE_ROLE|GRANT_PRIVILEGE|REVOKE_PRIVILEGE)$") String operation,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String targetUserId,
            @Pattern(regexp = "^(CUSTOMER|OPERATIONS|ADMIN|AUDITOR)$") String roleCode,
            @Pattern(regexp = "^CHECKER$") String privilegeCode,
            @Size(max = 500) String reason) { }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "^(APPROVE|REJECT)$") String decision,
            @Size(max = 500) String decisionNote) { }
}
