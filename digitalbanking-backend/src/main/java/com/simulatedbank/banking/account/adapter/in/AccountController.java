package com.simulatedbank.banking.account.adapter.in;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.application.AccountAccessService;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * `API-ACC-01` `GET /api/v1/accounts` và `API-ACC-02` `GET /api/v1/accounts/{accountId}`
 * (`07` §8.2.2–8.2.3, OpenAPI `/accounts`, `/accounts/{accountId}`) — BE-ACC-05.
 *
 * <p>Bảo mật: authentication (401 khi thiếu/invalid/expired) và coarse authorization
 * ({@code ACCOUNT_READ_SELF} cho list; {@code ACCOUNT_READ_SELF} hoặc {@code ACCOUNT_READ} cho
 * detail) do security chain dùng chung thực thi trước handler
 * ({@code common.config.SecurityFilterChainConfig}); controller fail-closed khi snapshot phiên
 * vắng mặt, không decode token, không nhận {@code userId}/{@code customerId} từ query/body
 * (anti-IDOR theo cấu trúc). Phạm vi staff single-bank MVP và Audit C3 theo DEC-INT-008/009.</p>
 *
 * <p>Scope detail được chọn từ authority server-side của principal, không từ tham số client:
 * {@code ACCOUNT_READ} → service tái kiểm quyền và role staff.
 * {@code ACCOUNT_READ_SELF} → owner scope (mismatch → 403 + C3).</p>
 *
 * <p>GET là read thuần: không tạo/đổi row, không audit read thường, không cần idempotency.
 * {@code Cache-Control: no-store} (ApiSecurityHeadersFilter + response) và
 * {@code X-Correlation-ID} do trung gian dùng chung bảo đảm.</p>
 */
@RestController
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/accounts")
public class AccountController {

    static final String OWNER_READ = "ACCOUNT_READ_SELF";
    static final String STAFF_READ = "ACCOUNT_READ";

    private final AccountAccessService accounts;

    public AccountController(AccountAccessService accounts) {
        this.accounts = accounts;
    }

    /** `API-ACC-01`: chỉ tài khoản thuộc customer của principal; filter status tùy chọn. */
    @GetMapping
    public ResponseEntity<AccountsResponse> list(@RequestParam(name = "status", required = false) String status,
            HttpServletRequest servlet) {
        SessionAuthenticator.Session principal = AccountHttpSupport.principal(servlet);
        AccountAccessService.AccountList owned = accounts.listOwnAccounts(
                Long.parseLong(principal.userId()), AccountHttpSupport.status(status),
                AccountHttpSupport.correlationId(servlet));
        List<AccountSummaryResponse> items = owned.items().stream()
                .map(AccountHttpSupport::toSummary)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AccountsResponse(items, owned.asOf()));
    }

    /**
     * `API-ACC-02`: owner scope hoặc staff scope đã qua permission gate của security chain.
     * `accountId` sai định dạng → 400 trước persistence; vắng → 404; khác chủ → 403 + C3.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetailResponse> detail(@PathVariable String accountId,
            HttpServletRequest servlet) {
        long accountKey = AccountHttpSupport.parseId(accountId);
        SessionAuthenticator.Session principal = AccountHttpSupport.principal(servlet);
        String correlation = AccountHttpSupport.correlationId(servlet);
        Set<String> authorities = principal.authorities();
        AccountAccessService.AccountDetail detail;
        if (authorities.contains(STAFF_READ)) {
            detail = accounts.getAccountForStaff(accountKey, principal, correlation);
        } else if (authorities.contains(OWNER_READ)) {
            detail = accounts.getOwnAccount(Long.parseLong(principal.userId()), accountKey, correlation);
        } else {
            // Defense-in-depth: chain đã chặn trước handler; không bao giờ suy scope từ client.
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toDetail(detail));
    }

    private static AccountDetailResponse toDetail(AccountAccessService.AccountDetail detail) {
        AccountQuery.AccountView view = detail.account();
        return new AccountDetailResponse(
                Long.toString(view.accountId()),
                view.accountNumberMasked(),
                view.accountNumberLast4(),
                view.currency(),
                view.status().name(),
                AccountHttpSupport.money(view.availableBalance()),
                AccountHttpSupport.money(view.ledgerBalance()),
                view.asOf(),
                new AccountDetailResponse.Owner(Long.toString(view.customerId()), detail.ownerStatus().name()),
                view.createdAt(),
                view.updatedAt());
    }
}
