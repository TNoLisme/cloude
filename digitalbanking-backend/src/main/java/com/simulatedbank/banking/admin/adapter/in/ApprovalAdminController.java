package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.AdminApprovalView;
import com.simulatedbank.banking.admin.application.ApprovalReadAuthorization;
import com.simulatedbank.banking.admin.application.query.ApprovalListQuery;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalQueryService;
import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-APR-01/API-APR-02 (`GET /api/v1/admin/approvals`, `GET /api/v1/admin/approvals/{approvalId}`).
 *
 * <p>Read-only: no approval transition, provider call or business audit fact is produced. Coarse
 * authentication/authorization is the shared chain ({@code OPERATIONS}/{@code ADMIN}); the application layer
 * re-checks the trusted session and derives the actor scope ({@link ApprovalVisibility}) which is applied
 * inside the SQL predicate for both the page and its count. {@code makerId}/{@code checkerId} are optional
 * filters inside that scope and can never widen it.</p>
 *
 * <p>Query parsing is fail-closed through {@link AdminHttpSupport}: malformed enums, ids, instants, page/size
 * or sort values are {@code 400 VALIDATION_ERROR}. Detail hides unknown and out-of-scope identifiers behind the
 * same {@code 404 APPROVAL_NOT_FOUND} (anti-enumeration). Payload is the masked projection; no entity leaves
 * the module.</p>
 */
@RestController
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
@RequestMapping("/api/v1/admin/approvals")
public class ApprovalAdminController {

    private final ApprovalQueryService queries;
    private final ApprovalReadAuthorization authorization;
    private final AdminApprovalView view;
    private final Clock clock;

    public ApprovalAdminController(ApprovalQueryService queries, ApprovalReadAuthorization authorization,
            AdminApprovalView view, Clock clock) {
        this.queries = queries;
        this.authorization = authorization;
        this.view = view;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "requestType", required = false) String requestType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "makerId", required = false) String makerId,
            @RequestParam(name = "checkerId", required = false) String checkerId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "sort", required = false) String sort) {
        ApprovalVisibility visibility = authorization.requireVisibility();
        ApprovalListQuery query = new ApprovalListQuery(
                AdminHttpSupport.requestType(requestType),
                AdminHttpSupport.status(status),
                AdminHttpSupport.optionalId(makerId),
                AdminHttpSupport.optionalId(checkerId),
                AdminHttpSupport.instant(from),
                AdminHttpSupport.instant(to),
                AdminHttpSupport.integer(page),
                AdminHttpSupport.integer(size),
                sort);
        ApprovalPage result = queries.list(query, visibility);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(view.page(result, clock.instant()));
    }

    @GetMapping("/{approvalId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String approvalId) {
        ApprovalVisibility visibility = authorization.requireVisibility();
        long id = AdminHttpSupport.id(approvalId);
        Map<String, Object> body = queries.findDetail(id, visibility)
                // Unknown and out-of-scope are indistinguishable (07 §8.8.4 anti-enumeration).
                .map(view::detail)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
