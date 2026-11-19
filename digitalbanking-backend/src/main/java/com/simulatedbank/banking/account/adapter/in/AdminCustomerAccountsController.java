package com.simulatedbank.banking.account.adapter.in;

import com.simulatedbank.banking.account.application.AccountAccessService;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * `API-ADM-02` `GET /api/v1/admin/customers/{customerId}/accounts` (`07` §8.2.5, OpenAPI
 * `/admin/customers/{customerId}/accounts`) — BE-ACC-09.
 *
 * <p>Route do Account sở hữu (ACC-DR-11 resolved: Customer không đọc bảng account); không có
 * controller thứ hai cùng path. Authentication (401) và coarse authorization
 * ({@code ACCOUNT_READ}, 403 cho CUSTOMER/staff thiếu permission) do security chain dùng chung
 * thực thi trước handler; {@link AccountAccessService} tái kiểm permission từ authority snapshot
 * server-side (defense-in-depth). {@code customerId} là path param theo schema `ID` — không phải
 * danh tính actor, không được dùng để suy quyền; actor/scope không bao giờ lấy từ query/body
 * (anti-IDOR, anti cross-tenant).</p>
 *
 * <p>Response đúng `AccountsResponse` (`items[AccountSummary]`, `asOf`): số tài khoản luôn mask
 * (`**** **** <last4>`), money là chuỗi scale 2, không trả ciphertext/blind index/PII. Thứ tự
 * {@code account_id ASC} từ persistence; contract không có pagination/sort nên không mở tham số
 * đó. {@code status} optional đúng như rule của `API-ACC-01` (`07` §8.2.2; BE-ACC-01 §C rule 2),
 * giá trị lạ → 400 trước persistence.</p>
 *
 * <p>Staff có scope exact-ID toàn ngân hàng theo DEC-INT-008. Customer vắng trả 404
 * CUSTOMER_NOT_FOUND. Customer tồn tại chưa có account trả 200 với items rỗng.</p>
 */
@RestController
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerAccountsController {

    private final AccountAccessService accounts;

    public AdminCustomerAccountsController(AccountAccessService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{customerId}/accounts")
    public ResponseEntity<AccountsResponse> list(@PathVariable String customerId,
            @RequestParam(name = "status", required = false) String status,
            HttpServletRequest servlet) {
        long customerKey = AccountHttpSupport.parseId(customerId);
        SessionAuthenticator.Session principal = AccountHttpSupport.principal(servlet);
        AccountAccessService.AccountList page = accounts.listCustomerAccounts(
                customerKey,
                AccountHttpSupport.status(status),
                principal,
                AccountHttpSupport.correlationId(servlet));
        List<AccountSummaryResponse> items = page.items().stream()
                .map(AccountHttpSupport::toSummary)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AccountsResponse(items, page.asOf()));
    }
}
