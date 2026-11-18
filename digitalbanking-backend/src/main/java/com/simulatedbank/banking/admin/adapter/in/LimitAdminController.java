package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.LimitProposalCommand;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
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
 * API-LIM-02/API-LIM-03 (`POST /api/v1/admin/limits`, `POST /api/v1/admin/limits/{id}/approve`).
 *
 * <p>Reject decisions are fully implemented and idempotent. Approve and proposal creation remain fail-closed with
 * {@code 503 SERVICE_UNAVAILABLE} because {@code limit.api.applyApprovedLimitChange} and the pending
 * {@code limit_config} row have no runtime (ADM-OI-05); no partial approval is written.</p>
 */
@RestController
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
@RequestMapping("/api/v1/admin/limits")
public class LimitAdminController {

    private final ApprovalProposalService proposals;
    private final ApprovalDecisionService decisions;

    public LimitAdminController(ApprovalProposalService proposals, ApprovalDecisionService decisions) {
        this.proposals = proposals;
        this.decisions = decisions;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody LimitChangeHttpRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Step-Up-Challenge", required = false) String challengeRef,
            HttpServletRequest servlet) {
        AdminHttpSupport.requiredBody(request);
        ProposalResult result = proposals.proposeLimit(new LimitProposalCommand(
                AdminHttpSupport.id(request.customerId()), AdminHttpSupport.optionalId(request.accountId()),
                AdminHttpSupport.money(request.perTransactionLimit()),
                AdminHttpSupport.money(request.dailyLimit()), request.currency(),
                AdminHttpSupport.instant(request.effectiveFrom()), request.reason(), idempotencyKey,
                challengeRef, AdminHttpSupport.correlation(servlet), AdminHttpSupport.requestId(servlet)));
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
        DecisionResult result = decisions.decide(new DecisionCommand(key, ApprovalRequestType.LIMIT_CHANGE,
                decision, request.decisionNote(), challengeRef, AdminHttpSupport.correlation(servlet),
                AdminHttpSupport.requestId(servlet), idempotencyKey));
        return AdminHttpSupport.response(result.responseStatus(), result.responseBody(), result.replayed(), null);
    }
}
