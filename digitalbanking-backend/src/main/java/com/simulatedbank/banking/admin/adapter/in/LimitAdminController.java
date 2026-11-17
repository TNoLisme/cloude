package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.LimitApprovalBinding;
import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.ApprovalResponse;
import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.ChangeRequest;
import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.DecisionRequest;
import lombok.RequiredArgsConstructor;
import com.simulatedbank.banking.admin.application.LimitApprovalService;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/limits")
public class LimitAdminController {
    private final LimitApprovalService service;

    private final LimitAdminMapper mapper;

    @PostMapping
    public ResponseEntity<ApprovalResponse> propose(Authentication actor, @Valid @RequestBody ChangeRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) String challenge) {
        var change = mapper.toChange(request);
        var result = service.propose(actor, change, challenge(challenge), RequestContextHolder.current().orElseThrow());
        return ResponseEntity.created(URI.create("/api/v1/admin/approvals/" + result.approval().approvalId()))
                .body(mapper.toResponse(result, false));
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponse decide(Authentication actor, @PathVariable String id, @Valid @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-Step-Up-Challenge", required = false) String challenge) {
        return mapper.toResponse(service.decide(actor, LimitApprovalBinding.id(id), request.decision().name(),
                request.decisionNote(), challenge(challenge), RequestContextHolder.current().orElseThrow()), true);
    }

    private static UUID challenge(String value) {
        if (value == null) return null;
        try {
            var parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException invalid) { throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR); }
    }

}
