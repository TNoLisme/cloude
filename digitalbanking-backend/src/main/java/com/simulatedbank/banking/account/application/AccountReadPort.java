package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port đọc persistence của Account (BE-ACC-03), chỉ dùng trong module.
 *
 * <p>Chỉ gồm các truy vấn đã duyệt theo {@code design/05} §7.1 và {@code 04} §4.3.1:
 * theo ID, theo blind index (unique {@code uq_account_no_hash}) và theo customer (index
 * {@code ix_account_customer}). Không có truy vấn toàn bảng, không filter/sort do client điều
 * khiển, không trả entity/ciphertext/blind index ra ngoài module.</p>
 *
 * <p>Không có operation ghi: account provisioning chưa được duyệt (ACC-DR-04 BLOCKED) nên
 * production code không mở write path. {@link AccountRecord} là projection tối thiểu phục vụ read
 * contract và không chứa {@code account_no_enc}/{@code account_no_hash}.</p>
 *
 * <p>Transaction: đọc thuần; lỗi persistence kỹ thuật lan truyền nguyên vẹn (fail closed), không
 * đổi thành {@code Optional.empty()} hay kết quả rỗng.</p>
 */
public interface AccountReadPort {

    /**
     * Projection đọc của một account. Không mang PII đầy đủ: chỉ {@code accountNumberLast4} để
     * tạo mask `**** **** <last4>`; không có ciphertext/blind index.
     */
    record AccountRecord(
            long accountId,
            long customerId,
            AccountStatus status,
            String currency,
            BigDecimal availableBalance,
            BigDecimal ledgerBalance,
            String accountNumberLast4,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    Optional<AccountRecord> findById(long accountId);

    /** Tra theo HMAC-SHA256 blind index (lowercase hex 64) — access path duy nhất cho số tài khoản. */
    Optional<AccountRecord> findByAccountNumberHash(String accountNumberHash);

    /**
     * Danh sách account của một customer, thứ tự ổn định {@code account_id ASC}
     * (`ix_account_customer`, BE-ACC-02 §E).
     *
     * @param statusFilter {@code null} = không lọc status
     */
    List<AccountRecord> findByCustomerId(long customerId, AccountStatus statusFilter);
}
