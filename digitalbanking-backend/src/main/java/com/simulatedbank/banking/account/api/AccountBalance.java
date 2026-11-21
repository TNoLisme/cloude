package com.simulatedbank.banking.account.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Hợp đồng public nội bộ money path của Account, chỉ dành cho orchestrator Transfer trong T2
 * (BE-ACC-01).
 *
 * <p>Nguồn: {@code docs-common/design/05. Modules contract.md} §7.2 (đã chốt signature tại
 * BE-ACC-01); {@code docs-common/design/02. consistency-concurrency.md} §5.3, Appendix A.3/A.4;
 * {@code docs-common/04. Database design & analysis.md} §4.3.1; [DEC-ACC-001](../../../../../../DECISIONS.md).</p>
 *
 * <h2>Transaction boundary</h2>
 * <p>Mọi operation **JOIN T2** — cùng connection/transaction với transfer, ledger, limit, audit.
 * Implementation dùng propagation {@code MANDATORY} (design/05 §3.2): gọi ngoài transaction là lỗi
 * lập trình và phải fail fast; provider KHÔNG tự mở/commit/rollback transaction. Caller đã lock row
 * transfer trước, sau đó provider lock account; khóa theo {@code ORDER BY account_id ASC} trong một
 * câu lệnh để giảm deadlock A→B/B→A (design/02 Appendix A.3). {@link LockedAccount} chỉ có hiệu lực
 * trong transaction hiện tại — không cache, không dùng như lock token sau commit.</p>
 *
 * <h2>Bất biến tiền tệ</h2>
 * <ul>
 *   <li>Không dùng {@code double}/{@code float}; amount {@code BigDecimal} &gt; 0, scale ≤ 2,
 *       currency {@code VND} (BE-CON-02/37, 07 §7.2.7 {@code Money}).</li>
 *   <li>Debit/credit là conditional UPDATE trên {@code available_balance} và {@code ledger_balance}
 *       (04 §4.3.1, design/02 A.4); không read-modify-write, không để số dư âm.</li>
 *   <li>Không ghi {@code ledger_entry} — Ledger nhận {@code BalanceAfter} từ Account.</li>
 * </ul>
 *
 * <h2>Lỗi</h2>
 * <ul>
 *   <li>{@code ACCOUNT_NOT_FOUND} (404): ID không tồn tại khi lock.</li>
 *   <li>{@code VALIDATION_ERROR} (400): ID không dương, source = destination, amount ≤ 0/scale &gt; 2.</li>
 *   <li>Credit 0 rows sau khi debit đã áp dụng → system error (không phải business outcome):
 *       ném lỗi để rollback toàn T2, không commit một phía.</li>
 *   <li>Timeout/deadlock/SQLSTATE kỹ thuật lan truyền nguyên vẹn cho orchestrator rollback/retry
 *       toàn transaction (BE-CON-54); provider không retry từng câu SQL.</li>
 * </ul>
 */
public interface AccountBalance {

    /**
     * Snapshot account sau khi đã {@code FOR UPDATE} trong T2. {@code status} là trạng thái thật;
     * provider không tự reject non-ACTIVE ở bước lock — caller/provider phối hợp re-check ACTIVE,
     * currency và source ownership trước khi ghi tiền (design/05 §7.2).
     */
    record LockedAccount(
            long accountId,
            long customerId,
            AccountQuery.Status status,
            String currency,
            BigDecimal availableBalance,
            BigDecimal ledgerBalance
    ) {
        public LockedAccount {
            AccountContractChecks.requireId(accountId, "accountId");
            AccountContractChecks.requireId(customerId, "customerId");
            AccountContractChecks.requireStatus(status);
            AccountContractChecks.requireVnd(currency);
            AccountContractChecks.requireBalance(availableBalance, "availableBalance");
            AccountContractChecks.requireBalance(ledgerBalance, "ledgerBalance");
        }
    }

    /** Hai row đã lock theo thứ tự {@code account_id ASC}; source và destination phải khác nhau. */
    record LockedAccounts(LockedAccount source, LockedAccount destination) {
        public LockedAccounts {
            Objects.requireNonNull(source, "source is required");
            Objects.requireNonNull(destination, "destination is required");
            if (source.accountId() == destination.accountId()) {
                throw new IllegalArgumentException("source and destination accounts must differ");
            }
        }
    }

    /** Số dư sau mutation, đọc từ {@code RETURNING} của conditional UPDATE (04 §4.3.1). */
    record BalanceAfter(BigDecimal availableBalance, BigDecimal ledgerBalance) {
        public BalanceAfter {
            AccountContractChecks.requireBalance(availableBalance, "availableBalance");
            AccountContractChecks.requireBalance(ledgerBalance, "ledgerBalance");
        }
    }

    /**
     * Kết quả conditional debit. {@code INSUFFICIENT_BALANCE}/{@code INACTIVE} chỉ xảy ra khi
     * UPDATE khớp 0 row — chưa có debit nào được áp dụng nên transaction vẫn có thể kết thúc
     * business-failed an toàn theo quyết định của orchestrator.
     */
    enum DebitOutcome {
        APPLIED,
        INSUFFICIENT_BALANCE,
        INACTIVE
    }

    /** {@code balanceAfter} bắt buộc khi {@code APPLIED}, phải {@code null} khi bị từ chối. */
    record DebitResult(DebitOutcome outcome, BalanceAfter balanceAfter) {
        public DebitResult {
            Objects.requireNonNull(outcome, "outcome is required");
            if ((outcome == DebitOutcome.APPLIED) != (balanceAfter != null)) {
                throw new IllegalArgumentException("balanceAfter is required only when debit is applied");
            }
        }
    }

    /**
     * Kết quả credit. Luôn có {@code balanceAfter}: credit 0 row/DB lỗi sau debit thành công là
     * technical failure, không phải business result.
     */
    record CreditResult(BalanceAfter balanceAfter) {
        public CreditResult {
            Objects.requireNonNull(balanceAfter, "balanceAfter is required");
        }
    }

    /**
     * Lock source + destination theo {@code account_id ASC} bằng một câu lệnh
     * {@code SELECT … ORDER BY account_id FOR UPDATE} (design/02 A.3).
     *
     * @throws com.simulatedbank.banking.common.error.BankingBusinessException
     *         {@code VALIDATION_ERROR} khi cùng ID/ID không dương, {@code ACCOUNT_NOT_FOUND} khi
     *         một row không tồn tại
     */
    LockedAccounts lockTransferAccounts(long sourceAccountId, long destinationAccountId);

    /** Empty means a row was missing under lock. Technical errors still abort T2. */
    Optional<LockedAccounts> findAndLockTransferAccounts(long sourceAccountId, long destinationAccountId);

    /**
     * Conditional debit: {@code UPDATE … SET available_balance = available_balance - :amount,
     * ledger_balance = ledger_balance - :amount WHERE account_id = :id AND status = 'ACTIVE' AND
     * available_balance >= :amount RETURNING available_balance, ledger_balance}.
     *
     * <p>Phải gọi sau {@link #lockTransferAccounts(long, long)} trong cùng T2. 0 row được phân loại
     * {@code INSUFFICIENT_BALANCE} hoặc {@code INACTIVE} sau khi đọc trạng thái dưới lock; không
     * bao giờ trả số dư âm.</p>
     */
    DebitResult debitConditional(long sourceAccountId, BigDecimal amount);

    /**
     * Conditional credit: {@code UPDATE … SET available_balance = available_balance + :amount,
     * ledger_balance = ledger_balance + :amount WHERE account_id = :id AND status = 'ACTIVE'
     * RETURNING …}.
     *
     * <p>Phải gọi sau debit thành công trong cùng T2. 0 row/DB lỗi → ném technical error để
     * rollback toàn T2 (không commit FAILED trong transaction đang có debit).</p>
     */
    CreditResult credit(long destinationAccountId, BigDecimal amount);

    /** Empty means a successful SQL statement updated zero rows. Use only under a money savepoint. */
    Optional<CreditResult> creditUnderSavepoint(long destinationAccountId, BigDecimal amount);
}
