package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-ROL-01/API-ROL-02 (`POST /api/v1/admin/roles`, `POST /api/v1/admin/roles/{id}/approve`).
 *
 * <p>Authentication/coarse authorization is enforced by the shared security chain
 * ({@code ROLE_MANAGE} / {@code CHECKER}+{@code ROLE_MANAGE}); the application services re-check the trusted
 * session and Maker–Checker rules. {@code Idempotency-Key} is required for both writes. The controller only maps
 * HTTP fields and the already-masked response map; it never sees an entity.</p>
 */
@RestController
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
@RequestMapping("/api/v1/admin/roles")
public class RoleAdminController {

    private final ApprovalProposalService proposals;
    private final ApprovalDecisionService decisions;

    public RoleAdminController(ApprovalProposalService proposals, ApprovalDecisionService decisions) {
        this.proposals = proposals;
        this.decisions = decisions;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody RoleChangeHttpRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Step-Up-Challenge", required = false) String challengeRef,
            HttpServletRequest servlet) {
        AdminHttpSupport.requiredBody(request);
        RoleChangeOperation operation = AdminHttpSupport.operation(request.operation());
        long targetUserId = AdminHttpSupport.id(request.targetUserId());
        ProposalResult result = proposals.proposeRole(new RoleProposalCommand(operation, targetUserId,
                request.roleCode(), request.privilegeCode(), request.reason(), idempotencyKey, challengeRef,
                AdminHttpSupport.correlation(servlet), AdminHttpSupport.requestId(servlet)));
        return AdminHttpSupport.response(result.responseStatus(), result.responseBody(), result.replayed(),
                result.approvalId());
    }

    @PostMapping("/{approvalId}/approve")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String approvalId,
            @RequestBody ApprovalDecisionHttpRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Step-Up-Challenge", required = false) String challengeRef,
            HttpServletRequest servlet) {
        AdminHttpSupport.requiredBody(request);
        long key = AdminHttpSupport.id(approvalId);
        ApprovalDecision decision = AdminHttpSupport.decision(request.decision());
        DecisionResult result = decisions.decide(new DecisionCommand(key, ApprovalRequestType.ROLE_CHANGE,
                decision, request.decisionNote(), challengeRef, AdminHttpSupport.correlation(servlet),
                AdminHttpSupport.requestId(servlet), idempotencyKey));
        return AdminHttpSupport.response(result.responseStatus(), result.responseBody(), result.replayed(), null);
    }
}
