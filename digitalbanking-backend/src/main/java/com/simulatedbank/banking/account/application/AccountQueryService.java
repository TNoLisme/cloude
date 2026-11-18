package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.domain.AccountNumber;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementor duy nhất của {@link AccountQuery} (BE-ACC-04).
 *
 * <p>Nguồn: {@code design/05} §7.1, {@code 04} §4.3.1, {@code 07} §8.2.2–8.2.3, OpenAPI
 * {@code AccountSummary}/{@code AccountDetail}. Read thuần {@code R}: không side effect, không
 * decrypt số tài khoản, không trả ciphertext/blind index.</p>
 *
 * <h2>Ownership (AC-024)</h2>
 * <ul>
 *   <li>Account không tồn tại → {@code 404 ACCOUNT_NOT_FOUND}, không audit (design/01 §5.7).</li>
 *   <li>Owner scope và {@code customer_id} khác principal → audit C3
 *       {@code SECURITY_ACCESS_DENIED} rồi {@code 403 AUTH_FORBIDDEN}; không trả bất kỳ field
 *       account nào trước khi kiểm xong.</li>
 *   <li>Staff scope ({@code AccountViewQuery.forStaff}) không kiểm ownership cá nhân — caller đã
 *       enforce permission {@code ACCOUNT_READ}; data scope theo chi nhánh/segment chưa được duyệt
 *       (ACC-DR-13) nên provider không tự chế điều kiện.</li>
 * </ul>
 *
 * <h2>Lỗi</h2>
 * <p>Input sai (ID không dương, số tài khoản sai format, correlation sai) → {@code 400
 * VALIDATION_ERROR} trước khi chạm persistence. Lỗi crypto/masking → {@code 500 INTERNAL_ERROR}
 * (fail closed). Lỗi persistence kỹ thuật lan truyền nguyên vẹn, không bị nuốt thành empty/404.</p>
 */
@Service
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class AccountQueryService implements AccountQuery {

    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private final AccountReadPort accounts;
    private final AccountNumberProtection protection;
    private final AccountAuditPort audit;
    private final Clock clock;

    public AccountQueryService(
            AccountReadPort accounts,
            AccountNumberProtection protection,
            AccountAuditPort audit,
            Clock clock) {
        this.accounts = accounts;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Optional<AccountSnapshot> findAccount(long accountId) {
        requirePositiveId(accountId);
        return accounts.findById(accountId)
                .map(account -> AccountViewMapper.toSnapshot(account, protection));
    }

    @Override
    public Optional<AccountSnapshot> resolveDestination(String accountNumber) {
        AccountNumber number = parseAccountNumber(accountNumber);
        String blindIndex = blindIndex(number);
        return accounts.findByAccountNumberHash(blindIndex)
                .map(account -> AccountViewMapper.toSnapshot(account, protection));
    }

    @Override
    public AccountView getAccountView(AccountViewQuery query) {
        Objects.requireNonNull(query, "query is required");
        AccountReadPort.AccountRecord account = accounts.findById(query.accountId())
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (query.ownerScope() && account.customerId() != query.ownerCustomerId()) {
            recordAccessDenied(query.ownerCustomerId(), account.accountId(), query.correlationId());
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return AccountViewMapper.toView(account, protection, clock.instant());
    }

    @Override
    public void assertOwnership(long accountId, long customerId, String correlationId) {
        requirePositiveId(accountId);
        requirePositiveId(customerId);
        requireCorrelationId(correlationId);
        AccountReadPort.AccountRecord account = accounts.findById(accountId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.customerId() != customerId) {
            recordAccessDenied(customerId, account.accountId(), correlationId);
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    /**
     * C3 là best-effort (design/01 §5.3): adapter đã tự bắt lỗi + fallback log/metric, nhưng lỗi
     * audit không được phép đổi quyết định 403 thành 500 hay bỏ qua kiểm tra ownership.
     */
    private void recordAccessDenied(long actorCustomerId, long accountId, String correlationId) {
        try {
            audit.recordStandalone(
                    AccountAuditPort.AccountAuditEvent.accessDenied(actorCustomerId, accountId),
                    correlationId);
        } catch (RuntimeException auditUnavailable) {
            // Không log lại PII/exception ở đây; adapter chịu trách nhiệm fallback an toàn.
        }
    }

    private AccountNumber parseAccountNumber(String accountNumber) {
        try {
            return AccountNumber.parse(accountNumber);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private String blindIndex(AccountNumber number) {
        try {
            return protection.blindIndex(number.digits());
        } catch (RuntimeException failure) {
            // Fail closed and do not chain crypto diagnostics (no plaintext/ciphertext in errors).
            throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void requirePositiveId(long value) {
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireCorrelationId(String correlationId) {
        if (correlationId == null || !CORRELATION_ID.matcher(correlationId).matches()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
