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
 * <p>Hai giới hạn đã ghi nhận, không tự chế hợp đồng: (1) data scope staff theo chi nhánh/segment
 * chưa được duyệt (ACC-DR-13) nên gate hiện tại là permission {@code ACCOUNT_READ}; (2) `404
 * CUSTOMER_NOT_FOUND` chưa phát được vì module Customer chưa có operation existence theo
 * {@code customerId} (ACC-DR-10) — customer không tồn tại trả `200 {items: [], asOf}` như customer
 * chưa có tài khoản, không lộ dữ liệu. Xem BE-ACC-09 §G.</p>
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
                principal.authorities(),
                AccountHttpSupport.correlationId(servlet));
        List<AccountSummaryResponse> items = page.items().stream()
                .map(AccountHttpSupport::toSummary)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AccountsResponse(items, page.asOf()));
    }
}
