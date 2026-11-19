package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case lock hai tài khoản cho T2 của Transfer (BE-ACC-06).
 *
 * <p>Nguồn: {@code design/02. consistency-concurrency.md} §7.3/§7.4/A.3, §11.3;
 * {@code design/05} §7.2 {@code AccountBalance.lockTransferAccounts}; {@code 04} §4.3.1;
 * {@code 07} §2.6. Hành vi:</p>
 *
 * <ul>
 *   <li>ID không dương hoặc source = destination → {@code 400 VALIDATION_ERROR} trước SQL
 *       (không lock row nào).</li>
 *   <li>Lock source + destination trong **một** câu lệnh {@code ORDER BY account_id FOR UPDATE}
 *       (thứ tự cố định giảm deadlock A→B/B→A; không bảo đảm tuyệt đối không bao giờ deadlock).</li>
 *   <li>Thiếu row → {@code 404 ACCOUNT_NOT_FOUND}; transaction do caller quyết định rollback hay
 *       kết thúc business-failed. Không lock row không liên quan.</li>
 *   <li>Propagation {@code MANDATORY}: gọi ngoài T2 là lỗi lập trình, fail fast; provider không
 *       tự mở/commit/rollback transaction và không giữ lock sau khi transaction kết thúc.</li>
 *   <li>Timeout/deadlock/SQLSTATE kỹ thuật (55P03/40P01/57014) lan truyền nguyên vẹn cho
 *       orchestrator retry toàn transaction — không retry từng câu SQL, không nuốt thành 404.</li>
 * </ul>
 *
 * <p>Đây là primitive của {@code AccountBalance}; debit/credit conditional thuộc BE-ACC-07 và
 * chưa được mở ở task này.</p>
 */
@Service
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class AccountLockService {

    private final AccountLockPort accounts;

    public AccountLockService(AccountLockPort accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AccountBalance.LockedAccounts lockTransferAccounts(long sourceAccountId, long destinationAccountId) {
        return findAndLockTransferAccounts(sourceAccountId, destinationAccountId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AccountBalance.LockedAccounts> findAndLockTransferAccounts(long sourceAccountId, long destinationAccountId) {
        if (sourceAccountId <= 0 || destinationAccountId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (sourceAccountId == destinationAccountId) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<AccountLockPort.LockedAccountRow> rows = accounts.lockTwoForUpdateOrderByIdAsc(
                sourceAccountId, destinationAccountId);
        if (rows.size() != 2) {
            return Optional.empty();
        }
        AccountBalance.LockedAccount source = null;
        AccountBalance.LockedAccount destination = null;
        for (AccountLockPort.LockedAccountRow row : rows) {
            AccountBalance.LockedAccount locked = toLocked(row);
            if (row.accountId() == sourceAccountId) {
                source = locked;
            } else if (row.accountId() == destinationAccountId) {
                destination = locked;
            }
        }
        if (source == null || destination == null) {
            // Không thể xảy ra với WHERE account_id IN (?, ?) + ORDER BY; fail closed nếu có.
            throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return Optional.of(new AccountBalance.LockedAccounts(source, destination));
    }

    private static AccountBalance.LockedAccount toLocked(AccountLockPort.LockedAccountRow row) {
        return new AccountBalance.LockedAccount(
                row.accountId(),
                row.customerId(),
                AccountQuery.Status.valueOf(row.status().name()),
                row.currency(),
                row.availableBalance(),
                row.ledgerBalance());
    }
}
