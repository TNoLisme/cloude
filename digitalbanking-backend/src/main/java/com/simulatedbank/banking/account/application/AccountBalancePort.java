package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.domain.AccountStatus;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Port persistence cho conditional debit/credit của T2 (BE-ACC-07).
 *
 * <p>Nguồn: {@code docs-common/design/02. consistency-concurrency.md} §5.3 bước 6, §7.4,
 * §8.2 (SQL canonical), Appendix A.3/A.4; {@code docs-common/design/05. Modules contract.md}
 * §7.2; {@code docs-common/04. Database design & analysis.md} §4.3.1 (CHECK
 * {@code available_balance >= 0}, {@code available_balance <= ledger_balance},
 * {@code NUMERIC(19,2)}).</p>
 *
 * <h2>Transaction</h2>
 * <p>Mọi method là {@code MANDATORY}: chỉ có nghĩa trong T2 đã mở bởi orchestrator, dùng chung
 * connection/transaction với lock account, ledger, usage và audit. Gọi ngoài transaction là lỗi
 * lập trình và fail fast — port không tự mở/commit/rollback, không retry câu SQL, không giữ
 * snapshot sau commit.</p>
 *
 * <h2>SQL</h2>
 * <p>Debit/credit là **một câu conditional UPDATE** trên cả {@code available_balance} và
 * {@code ledger_balance} với {@code status = 'ACTIVE'} và {@code RETURNING}. Không
 * read-modify-write ở application, không optimistic {@code @Version} (ACC-DR-07), không
 * {@code SKIP LOCKED}/{@code NOWAIT} cho money path. Vì caller đã giữ row lock
 * ({@link AccountLockPort}), conditional update là defense-in-depth trước code path không lock;
 * CHECK constraint là chốt cuối.</p>
 *
 * <p>Không trả ciphertext/blind index/PII. Lỗi kỹ thuật (55P03/40P01/40001, 23514, 22003, timeout)
 * lan truyền nguyên vẹn để orchestrator rollback toàn T2 (BE-CON-54).</p>
 */
public interface AccountBalancePort {

    /**
     * Row sau mutation, đọc từ {@code RETURNING} của conditional UPDATE — không phải snapshot đọc
     * lại bằng câu SELECT riêng.
     */
    record BalanceRow(
            long accountId,
            AccountStatus status,
            BigDecimal availableBalance,
            BigDecimal ledgerBalance
    ) {
    }

    /**
     * {@code UPDATE banking.account SET available_balance = available_balance - :amount,
     * ledger_balance = ledger_balance - :amount, updated_at = now() WHERE account_id = :id AND
     * status = 'ACTIVE' AND available_balance >= :amount RETURNING …}.
     *
     * @return row sau update khi áp dụng; empty khi 0 row (không đủ số dư hoặc không ACTIVE) —
     *         caller phân loại sau khi đọc trạng thái dưới lock
     */
    Optional<BalanceRow> debitIfActiveAndSufficient(long accountId, BigDecimal amount);

    /**
     * {@code UPDATE banking.account SET available_balance = available_balance + :amount,
     * ledger_balance = ledger_balance + :amount, updated_at = now() WHERE account_id = :id AND
     * status = 'ACTIVE' RETURNING …}.
     *
     * @return row sau update khi áp dụng; empty là bất thường (caller đã re-check ACTIVE dưới
     *         lock) — service coi là system error và rollback T2
     */
    Optional<BalanceRow> creditIfActive(long accountId, BigDecimal amount);

    /**
     * Đọc lại một row bằng {@code SELECT … WHERE account_id = ? FOR UPDATE} để phân loại kết quả
     * 0 row của conditional debit trong cùng T2.
     *
     * <p>Dùng {@code FOR UPDATE} để phân loại dựa trên bản committed mới nhất và không đọc trạng
     * thái ngoài lock; nếu caller đã lock (đúng hợp đồng) thì việc lock lại là vô hại. Empty khi
     * row không tồn tại.</p>
     */
    Optional<BalanceRow> findByIdForUpdate(long accountId);
}
