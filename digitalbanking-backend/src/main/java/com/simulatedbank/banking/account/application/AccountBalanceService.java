package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation duy nhất của {@code account.api.AccountBalance} (BE-ACC-07).
 *
 * <p>Nguồn: {@code docs-common/design/05. Modules contract.md} §7.2; {@code docs-common/design/02.
 * consistency-concurrency.md} §5.3 bước 3/6, §7.3/§7.4, §8.2–§8.3, §11.5; {@code docs-common/07.
 * API contracts.md} §2.3 {@code Money} ({@code > 0}, scale ≤ 2, tối đa 17 chữ số phần nguyên);
 * {@code docs-common/04. Database design & analysis.md} §4.3.1; {@code docs-common/03. SRS.md}
 * FR-012/FR-015; [BE-ACC-06](BE-ACC-06.md).</p>
 *
 * <h2>Hành vi</h2>
 * <ul>
 *   <li>Lock: ủy quyền primitive BE-ACC-06 ({@link AccountLockService}) — cùng một câu
 *       {@code ORDER BY account_id FOR UPDATE}, không duplicate SQL.</li>
 *   <li>Debit: conditional UPDATE {@code ACTIVE + available_balance >= amount}; 0 row → đọc lại
 *       dưới lock và phân loại {@code INACTIVE} hoặc {@code INSUFFICIENT_BALANCE} (business
 *       outcome, chưa có thay đổi tiền).</li>
 *   <li>Credit: conditional UPDATE {@code ACTIVE}; 0 row là bất thường sau khi đã debit →
 *       {@code INTERNAL_ERROR} để rollback toàn T2, không commit một phía.</li>
 *   <li>Amount sai (null/≤ 0/scale &gt; 2/&gt; 17 chữ số nguyên) hoặc accountId ≤ 0 →
 *       {@code VALIDATION_ERROR} trước mọi SQL.</li>
 *   <li>Propagation {@code MANDATORY}: gọi ngoài T2 fail fast; service không mở/commit/rollback
 *       transaction, không retry câu SQL, không phát sinh compensating credit/debit.</li>
 * </ul>
 *
 * <p>Không kiểm ownership ở đây vì hợp đồng đã chốt không nhận {@code customerId} (BE-ACC-01,
 * thay đổi signature là breaking change): T2 re-check ownership bằng
 * {@code LockedAccount.customerId()} + {@code AccountQuery.assertOwnership} trước khi gọi debit.
 * Idempotency cũng không thuộc Account — xem BE-ACC-07 §F.</p>
 */
@Service
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class AccountBalanceService implements AccountBalance {

    private static final int MONEY_SCALE = 2;
    private static final int MONEY_INTEGER_DIGITS = 17;

    private final AccountLockService locks;
    private final AccountBalancePort balances;

    public AccountBalanceService(AccountLockService locks, AccountBalancePort balances) {
        this.locks = locks;
        this.balances = balances;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccounts lockTransferAccounts(long sourceAccountId, long destinationAccountId) {
        return locks.lockTransferAccounts(sourceAccountId, destinationAccountId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedAccounts> findAndLockTransferAccounts(long sourceAccountId, long destinationAccountId) {
        return locks.findAndLockTransferAccounts(sourceAccountId, destinationAccountId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DebitResult debitConditional(long sourceAccountId, BigDecimal amount) {
        requireAccountId(sourceAccountId);
        requirePositiveAmount(amount);

        Optional<AccountBalancePort.BalanceRow> applied =
                balances.debitIfActiveAndSufficient(sourceAccountId, amount);
        if (applied.isPresent()) {
            return new DebitResult(DebitOutcome.APPLIED, balanceAfter(applied.get()));
        }

        AccountBalancePort.BalanceRow current = balances.findByIdForUpdate(sourceAccountId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        // design/02 §8.2: 0 row → status != ACTIVE là INACTIVE, ngược lại INSUFFICIENT_BALANCE.
        // Cả hai là business outcome: chưa có debit nào được áp dụng.
        DebitOutcome outcome = current.status() == AccountStatus.ACTIVE
                ? DebitOutcome.INSUFFICIENT_BALANCE
                : DebitOutcome.INACTIVE;
        return new DebitResult(outcome, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditResult credit(long destinationAccountId, BigDecimal amount) {
        return creditUnderSavepoint(destinationAccountId, amount)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CreditResult> creditUnderSavepoint(long destinationAccountId, BigDecimal amount) {
        requireAccountId(destinationAccountId);
        requirePositiveAmount(amount);
        return balances.creditIfActive(destinationAccountId, amount)
                .map(row -> new CreditResult(balanceAfter(row)));
    }

    private static BalanceAfter balanceAfter(AccountBalancePort.BalanceRow row) {
        return new BalanceAfter(row.availableBalance(), row.ledgerBalance());
    }

    private static void requireAccountId(long accountId) {
        if (accountId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * {@code Money} nội bộ: {@code > 0}, scale ≤ 2, tối đa 17 chữ số phần nguyên
     * ({@code NUMERIC(19,2)}). Không round, không clamp — caller phải gửi giá trị đúng.
     */
    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > MONEY_SCALE) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (amount.precision() - amount.scale() > MONEY_INTEGER_DIGITS) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
