package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.domain.AccountStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Port persistence cho pessimistic row lock của Account (BE-ACC-06).
 *
 * <p>Chỉ có đúng một truy vấn đã duyệt theo {@code design/02} §A.3/§7.3/§11.3 và
 * {@code design/05} §7.2: {@code SELECT … WHERE account_id IN (?, ?) ORDER BY account_id
 * FOR UPDATE}. Không dùng optimistic {@code @Version} (ACC-DR-07), không {@code SKIP LOCKED},
 * không {@code NOWAIT} cho money path, không lock theo thứ tự request.</p>
 *
 * <p>Transaction: {@code MANDATORY} — lock chỉ có nghĩa trong T2 hiện tại; gọi ngoài transaction
 * là lỗi lập trình và phải fail fast. Lock được PostgreSQL giữ tới commit/rollback của
 * transaction gọi; adapter không cache và không trả lock token.</p>
 *
 * <p>Projection không chứa ciphertext/blind index/PII; lỗi kỹ thuật (timeout, deadlock, SQLSTATE)
 * lan truyền nguyên vẹn cho orchestrator retry toàn transaction (BE-CON-54).</p>
 */
public interface AccountLockPort {

    /**
     * Row đã khóa của một account. Là snapshot **trong transaction hiện tại**, không dùng lại
     * sau commit.
     */
    record LockedAccountRow(
            long accountId,
            long customerId,
            AccountStatus status,
            String currency,
            BigDecimal availableBalance,
            BigDecimal ledgerBalance
    ) {
    }

    /**
     * Khóa hai row theo {@code account_id ASC} trong một câu lệnh {@code FOR UPDATE}.
     *
     * @return 0–2 row; caller phân loại thiếu row thành {@code ACCOUNT_NOT_FOUND}
     */
    List<LockedAccountRow> lockTwoForUpdateOrderByIdAsc(long firstAccountId, long secondAccountId);
}
