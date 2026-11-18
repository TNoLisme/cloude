package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountQuery.AccountView;
import com.simulatedbank.banking.account.api.AccountQuery.AccountViewQuery;
import com.simulatedbank.banking.account.api.AccountQuery.Status;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case đọc account theo principal đã xác thực (BE-ACC-04) — đầu vào cho REST adapter
 * {@code API-ACC-01}/{@code API-ACC-02} (BE-ACC-05).
 *
 * <p>Identity resolution: caller CHỈ truyền {@code userId} lấy từ principal server-side
 * ({@code SessionAuthenticator.Session} qua {@code SessionAuthenticationFilter}); service tự
 * resolve {@code userId → customerId/status} bằng {@link CustomerQuery} của module Customer
 * (design/05 §6, DEC-CUS-003). Không nhận/không tin {@code customerId} do client gửi — đây là
 * biên chống IDOR: quyền sở hữu luôn suy ra từ session, không từ request.</p>
 *
 * <p>Mapping vắng ({@code CustomerQuery.findByUserId} empty) → {@code 404 CUSTOMER_NOT_FOUND},
 * cùng tiền lệ {@code CustomerProfileService}; không tự tạo Customer row và không trả danh sách
 * rỗng giả. Customer {@code INACTIVE} vẫn được đọc kèm status thật: chính sách tác động lên
 * Account là CUS-OI-06 <b>chưa được duyệt</b> nên service không tự thêm điều kiện từ chối
 * (không suy diễn contract).</p>
 *
 * <p>Account owner mismatch do {@link AccountQueryService#getAccountView} enforce
 * ({@code 403 AUTH_FORBIDDEN} + audit C3). REST adapter là
 * {@code account.adapter.in.AccountController} (BE-ACC-05); authentication/authorization thô,
 * header và cache do security chain/Common filter bảo đảm, không nhân bản ở đây.</p>
 *
 * <p>Staff list ({@code API-ADM-02}, BE-ACC-09) là ngoại lệ có chủ đích: {@code customerId} lấy
 * từ path để chọn tập account, không phải danh tính actor, và gate là permission
 * {@code ACCOUNT_READ} đã duyệt (data scope chi nhánh/segment còn BLOCKED — ACC-DR-13). Service
 * không resolve {@code userId → customerId} cho use case này và không kiểm ownership cá nhân.</p>
 */
@Service
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class AccountAccessService {

    /** Permission đã duyệt của `API-ADM-02` (07 §3.4 seed: OPERATIONS/ADMIN/AUDITOR; §8.2.5). */
    static final String STAFF_READ_PERMISSION = "ACCOUNT_READ";

    /** Customer actor marker: a customer principal never satisfies a staff lookup (DAR-001). */
    static final String CUSTOMER_ROLE_AUTHORITY = "ROLE_CUSTOMER";

    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private final CustomerQuery customers;
    private final AccountReadPort accounts;
    private final AccountNumberProtection protection;
    private final AccountQuery accountQuery;
    private final Clock clock;

    public AccountAccessService(
            CustomerQuery customers,
            AccountReadPort accounts,
            AccountNumberProtection protection,
            AccountQuery accountQuery,
            Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.protection = protection;
        this.accountQuery = accountQuery;
        this.clock = clock;
    }

    /**
     * Kết quả list: projection masked + đúng một {@code asOf} cho cả response (kể cả khi danh
     * sách rỗng, adapter vẫn có snapshot time hợp lệ để trả {@code AccountsResponse}).
     */
    public record AccountList(List<AccountView> items, Instant asOf) {
        public AccountList {
            items = List.copyOf(items);
            Objects.requireNonNull(asOf, "asOf is required");
        }
    }

    /**
     * Danh sách account của chính customer đang đăng nhập (`API-ACC-01` use case).
     *
     * @param userId        principal server-side, không phải giá trị client
     * @param statusFilter  {@code null} = không lọc; giá trị khác enum đã bị validate ở adapter
     * @param correlationId trace của request hiện tại
     * @return projection masked, thứ tự {@code account_id ASC}
     */
    public AccountList listOwnAccounts(long userId, Status statusFilter, String correlationId) {
        requirePositiveId(userId);
        requireCorrelationId(correlationId);
        long customerId = resolveCustomerId(userId);
        Instant asOf = clock.instant();
        List<AccountView> items = accounts.findByCustomerId(customerId, toDomainStatus(statusFilter)).stream()
                .map(account -> AccountViewMapper.toView(account, protection, asOf))
                .toList();
        return new AccountList(items, asOf);
    }

    /**
     * Danh sách account của một customer cho staff (`API-ADM-02` use case).
     *
     * <p>Authorization: security chain đã chặn {@code ACCOUNT_READ} trước handler; service tái
     * kiểm permission từ authority snapshot tin cậy (defense-in-depth, cùng pattern
     * {@code StaffCustomerDetailService}). {@code customerId} đến từ path nhưng KHÔNG dùng để suy
     * actor/ownership — staff scope theo hợp đồng {@code 07} §8.2.5 chỉ giới hạn permission, chưa
     * có model data scope (ACC-DR-13 BLOCKED), nên service không tự chế điều kiện chi nhánh/segment
     * và không kiểm ownership cá nhân.</p>
     *
     * <p>Existence: {@code customer.api.CustomerQuery.findByCustomerId} (exact-ID, PII-free) cho phép
     * phân biệt {@code 404 CUSTOMER_NOT_FOUND} với customer tồn tại nhưng chưa có account
     * ({@code 200 {items: [], asOf}}), đúng {@code 07} §8.2.5. Provider failure lan truyền nguyên
     * vẹn (fail closed), không bị đổi thành empty/404. Data scope chi nhánh/segment vẫn BLOCKED
     * (ACC-DR-13) nên service không tự chế điều kiện ngoài permission đã duyệt.</p>
     *
     * @param customerId       ID customer từ path (adapter đã validate schema `ID`)
     * @param statusFilter     {@code null} = không lọc
     * @param actorAuthorities authority snapshot server-side của staff
     * @param correlationId    trace của request hiện tại
     */
    public AccountList listCustomerAccounts(long customerId, Status statusFilter,
            Set<String> actorAuthorities, String correlationId) {
        requirePositiveId(customerId);
        requireCorrelationId(correlationId);
        requireStaffScope(actorAuthorities);
        // 07 §8.2.5: an unknown customer is 404 CUSTOMER_NOT_FOUND, not an empty page. The lookup is
        // exact-ID, PII-free and read-only (customer.api); Account never reads the customer table.
        if (customers.findByCustomerId(customerId).isEmpty()) {
            throw new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        Instant asOf = clock.instant();
        List<AccountView> items = accounts.findByCustomerId(customerId, toDomainStatus(statusFilter)).stream()
                .map(account -> AccountViewMapper.toView(account, protection, asOf))
                .toList();
        return new AccountList(items, asOf);
    }

    /**
     * Fail-closed staff gate: the permission must be present and the principal must not carry the
     * customer role. Identity already isolates permissions by {@code user_type} (JpaAuthorityReader),
     * so a mixed snapshot means the identity context is inconsistent and is denied instead of being
     * treated as staff (design/04 §5.1, DAR-001).
     */
    private static void requireStaffScope(Set<String> actorAuthorities) {
        if (actorAuthorities == null || !actorAuthorities.contains(STAFF_READ_PERMISSION)
                || actorAuthorities.contains(CUSTOMER_ROLE_AUTHORITY)) {
            // 403 trước persistence: không tiết lộ customer/account nào cho caller thiếu quyền.
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    /**
     * Chi tiết account của chính customer đang đăng nhập (`API-ACC-02` owner scope).
     *
     * @throws BankingBusinessException {@code CUSTOMER_NOT_FOUND} khi principal không có mapping,
     *         {@code ACCOUNT_NOT_FOUND} khi account vắng, {@code AUTH_FORBIDDEN} khi account thuộc
     *         customer khác (kèm audit C3)
     */
    public AccountView getOwnAccount(long userId, long accountId, String correlationId) {
        requirePositiveId(userId);
        requirePositiveId(accountId);
        requireCorrelationId(correlationId);
        long customerId = resolveCustomerId(userId);
        return accountQuery.getAccountView(
                AccountViewQuery.forOwner(accountId, customerId, correlationId));
    }

    /**
     * Chi tiết account cho staff đã qua permission gate `ACCOUNT_READ` của security chain
     * (`API-ACC-02` staff scope). Không kiểm ownership cá nhân và không suy data scope từ
     * client — data scope (chi nhánh/segment) chưa được duyệt (ACC-DR-13).
     *
     * @throws BankingBusinessException {@code ACCOUNT_NOT_FOUND} khi account vắng
     */
    public AccountView getAccountForStaff(long accountId, String correlationId) {
        requirePositiveId(accountId);
        requireCorrelationId(correlationId);
        return accountQuery.getAccountView(AccountViewQuery.forStaff(accountId, correlationId));
    }

    private long resolveCustomerId(long userId) {
        return customers.findByUserId(userId)
                .map(CustomerQuery.CustomerIdentity::customerId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private static AccountStatus toDomainStatus(Status status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case ACTIVE -> AccountStatus.ACTIVE;
            case LOCKED -> AccountStatus.LOCKED;
            case CLOSED -> AccountStatus.CLOSED;
        };
    }

    private static void requirePositiveId(long value) {
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireCorrelationId(String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (!CORRELATION_ID.matcher(correlationId).matches()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
