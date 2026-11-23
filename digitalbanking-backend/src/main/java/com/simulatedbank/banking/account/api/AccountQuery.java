package com.simulatedbank.banking.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Hợp đồng public nội bộ read-only của Account cho Transfer/Ledger/Admin/Account REST adapter
 * (BE-ACC-01).
 *
 * <p>Nguồn: {@code docs-common/design/05. Modules contract.md} §7.1 (đã chốt signature tại
 * BE-ACC-01); {@code docs-common/04. Database design & analysis.md} §4.3.1; {@code docs-common/07.
 * API contracts.md} §7.2.7–7.2.8, §8.2.2–8.2.3; {@code digitalbanking-docs/openapi/digital-banking-v1.yaml}
 * {@code AccountSummary}/{@code AccountDetail}/{@code AccountStatus}; [DEC-ACC-001](../../../../../../DECISIONS.md).</p>
 *
 * <p>Account là write-owner duy nhất của {@code banking.account}. Consumer chỉ gọi qua interface này,
 * KHÔNG import {@code account.adapter}, {@code account.application} hay {@code account.domain} và
 * KHÔNG đọc trực tiếp bảng. Mọi operation ở đây là đọc thuần ({@code R}, design/05 §3.2): không
 * side effect, không tạo/đổi row, không ghi audit C2.</p>
 *
 * <p>PII: chỉ trả số tài khoản đã mask (`**** **** <last4>`) + 4 ký tự cuối. Không có plaintext,
 * ciphertext (`account_no_enc`) hay blind index (`account_no_hash`) trong bất kỳ DTO nào; provider
 * không decrypt account number để phục vụ read contract này (04 §4.3.1).</p>
 *
 * <p>Lỗi: implementation ném {@code BankingBusinessException} theo catalog
 * {@code common.error.ErrorCode} — {@code ACCOUNT_NOT_FOUND} (404) khi không tồn tại,
 * {@code AUTH_FORBIDDEN} (403) khi owner mismatch (kèm audit {@code SECURITY_ACCESS_DENIED} C3),
 * {@code VALIDATION_ERROR} (400) khi input sai định dạng. Lỗi persistence kỹ thuật lan truyền
 * nguyên vẹn (fail closed), không bị nuốt hoặc đổi thành empty/404.</p>
 */
public interface AccountQuery {

    /**
     * Ba trạng thái đã duyệt của {@code banking.account.status} (04 §4.3.1 CHECK
     * {@code ck_account_status}; OpenAPI {@code AccountStatus}). Không tự thêm giá trị
     * (ví dụ {@code FROZEN} trong {@code _overview/03-account.md} là tài liệu cũ, không phải
     * DB/API contract — DEC-ACC-001). Enum nằm trong {@code api} để consumer không import
     * {@code account.domain}.
     */
    enum Status {
        ACTIVE,
        LOCKED,
        CLOSED
    }

    /**
     * Snapshot tối thiểu cho preflight/ownership của Transfer (design/05 §7.1).
     *
     * @param accountId           ID nội bộ ({@code BIGINT}, decimal string trên HTTP)
     * @param customerId          chủ sở hữu (không phải actor)
     * @param status              trạng thái thực tế trong DB; eligibility do consumer quyết định
     * @param currency            luôn {@code VND} (CHECK constraint MVP)
     * @param accountNumberMasked {@code **** **** <last4>} (OpenAPI pattern)
     * @param accountNumberLast4  4 ký tự cuối
     */
    record AccountSnapshot(
            long accountId,
            long customerId,
            Status status,
            String currency,
            String accountNumberMasked,
            String accountNumberLast4
    ) {
        public AccountSnapshot {
            AccountContractChecks.requireId(accountId, "accountId");
            AccountContractChecks.requireId(customerId, "customerId");
            AccountContractChecks.requireStatus(status);
            AccountContractChecks.requireVnd(currency);
            AccountContractChecks.requireMaskedNumber(accountNumberMasked, accountNumberLast4);
        }
    }

    /**
     * Projection masked đầy đủ cho {@code AccountSummary}/{@code AccountDetail} (07 §7.2.7–7.2.8).
     * Không mang PII chủ sở hữu: {@code owner.fullName} do Customer provider cung cấp và được
     * adapter Account ghép sau khi kiểm quyền (design/05 §6.2; DEC-ACC-001 ACC-DR-10).
     *
     * @param availableBalance số dư khả dụng snapshot tại {@code asOf}
     * @param ledgerBalance    số dư sổ cái snapshot tại {@code asOf}
     * @param asOf             thời điểm đọc số dư (UTC)
     */
    record AccountView(
            long accountId,
            long customerId,
            Status status,
            String currency,
            BigDecimal availableBalance,
            BigDecimal ledgerBalance,
            String accountNumberMasked,
            String accountNumberLast4,
            Instant createdAt,
            Instant updatedAt,
            Instant asOf
    ) {
        public AccountView {
            AccountContractChecks.requireId(accountId, "accountId");
            AccountContractChecks.requireId(customerId, "customerId");
            AccountContractChecks.requireStatus(status);
            AccountContractChecks.requireVnd(currency);
            AccountContractChecks.requireBalance(availableBalance, "availableBalance");
            AccountContractChecks.requireBalance(ledgerBalance, "ledgerBalance");
            AccountContractChecks.requireMaskedNumber(accountNumberMasked, accountNumberLast4);
            AccountContractChecks.requireInstant(createdAt, "createdAt");
            AccountContractChecks.requireInstant(updatedAt, "updatedAt");
            AccountContractChecks.requireInstant(asOf, "asOf");
        }
    }

    /**
     * Ngữ cảnh truy cập cho {@link #getAccountView(AccountViewQuery)}. Owner scope và staff scope
     * được tách bằng factory method để không tồn tại "null nghĩa là staff" do lỗi gọi nhầm.
     *
     * <p>Staff scope chỉ dùng sau khi caller đã xác thực session và enforce permission
     * {@code ACCOUNT_READ} từ snapshot server-side (07 §8.2.3, {@code SecurityFilterChainConfig});
     * provider vẫn đọc trạng thái/số dư thật và không nhận customerId/actor từ body/query client.
     * Data scope của staff (theo chi nhánh/segment) chưa được duyệt — ACC-DR-13 BLOCKED.</p>
     *
     * @param ownerCustomerId customer sở hữu kỳ vọng; {@code null} chỉ khi staff scope
     */
    record AccountViewQuery(long accountId, Long ownerCustomerId, String correlationId) {
        public AccountViewQuery {
            AccountContractChecks.requireId(accountId, "accountId");
            if (ownerCustomerId != null) {
                AccountContractChecks.requireId(ownerCustomerId, "ownerCustomerId");
            }
            AccountContractChecks.requireCorrelationId(correlationId);
        }

        /** Truy cập của chính chủ sở hữu: mismatch → 403 {@code AUTH_FORBIDDEN} + audit C3. */
        public static AccountViewQuery forOwner(long accountId, long customerId, String correlationId) {
            return new AccountViewQuery(accountId, customerId, correlationId);
        }

        /** Truy cập của staff đã qua permission gate; provider không kiểm ownership cá nhân. */
        public static AccountViewQuery forStaff(long accountId, String correlationId) {
            return new AccountViewQuery(accountId, null, correlationId);
        }

        public boolean ownerScope() {
            return ownerCustomerId != null;
        }
    }

    /**
     * Đọc snapshot theo {@code accountId} (dạng ID của {@code resolveDestination}; cần cho
     * preflight/T2 revalidation khi Transfer nhận {@code DestinationAccountId}).
     *
     * @return empty khi không tồn tại; lỗi kỹ thuật lan truyền, không trả empty
     */
    Optional<AccountSnapshot> findAccount(long accountId);

    /**
     * Phân giải tài khoản đích bằng số tài khoản (design/05 §7.1): normalize + HMAC-SHA256 blind
     * index do Account sở hữu, tra unique {@code account_no_hash}. Không log plaintext.
     *
     * <p>Định dạng input đã duyệt là {@code ^[0-9]{6,20}$} (OpenAPI {@code DestinationAccountNumber});
     * sai định dạng → {@code VALIDATION_ERROR} trước khi tra DB. Chính sách sinh số tài khoản
     * (độ dài/checksum/prefix) vẫn chưa được duyệt — ACC-DR-04 BLOCKED — nên provider không suy
     * diễn thêm quy tắc.</p>
     *
     * @return empty khi không có tài khoản khớp; không tiết lộ tồn tại của tài khoản khác
     */
    Optional<AccountSnapshot> resolveDestination(String accountNumber);

    /**
     * Projection masked theo scope. Owner scope: {@code ownerCustomerId} phải khớp
     * {@code account.customer_id}, nếu không → 403 {@code AUTH_FORBIDDEN} + audit C3 (AC-024);
     * account không tồn tại → 404 {@code ACCOUNT_NOT_FOUND}. Staff scope: chỉ đọc, permission đã
     * được enforce trước đó.
     */
    AccountView getAccountView(AccountViewQuery query);

    /**
     * Xác nhận {@code accountId} thuộc {@code customerId} (design/05 §7.1) — dùng bởi Transfer
     * (source ownership) và Ledger (ownership khi xem lịch sử). Không trả dữ liệu tài khoản.
     *
     * <p>Mismatch → 403 {@code AUTH_FORBIDDEN} + audit C3; không tồn tại → 404
     * {@code ACCOUNT_NOT_FOUND}. Ownership là điều kiện bắt buộc, không thay thế kiểm tra
     * status/balance dưới T2.</p>
     */
    void assertOwnership(long accountId, long customerId, String correlationId);
}
