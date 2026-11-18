package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Instant;

/**
 * Chuyển projection persistence sang DTO của {@code account.api} (BE-ACC-04).
 *
 * <p>Mask được tính từ {@code last4} qua {@link AccountNumberProtection}; không decrypt, không
 * truy cập ciphertext/blind index. Lỗi masking (dữ liệu last4 hỏng) → {@code INTERNAL_ERROR} và
 * không trả projection dở dang (fail closed).</p>
 */
final class AccountViewMapper {

    private AccountViewMapper() {
    }

    static AccountQuery.AccountSnapshot toSnapshot(
            AccountReadPort.AccountRecord account, AccountNumberProtection protection) {
        return new AccountQuery.AccountSnapshot(
                account.accountId(),
                account.customerId(),
                toPublicStatus(account.status()),
                account.currency(),
                mask(account.accountNumberLast4(), protection),
                account.accountNumberLast4());
    }

    static AccountQuery.AccountView toView(
            AccountReadPort.AccountRecord account, AccountNumberProtection protection, Instant asOf) {
        return new AccountQuery.AccountView(
                account.accountId(),
                account.customerId(),
                toPublicStatus(account.status()),
                account.currency(),
                account.availableBalance(),
                account.ledgerBalance(),
                mask(account.accountNumberLast4(), protection),
                account.accountNumberLast4(),
                account.createdAt(),
                account.updatedAt(),
                asOf);
    }

    static AccountQuery.Status toPublicStatus(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> AccountQuery.Status.ACTIVE;
            case LOCKED -> AccountQuery.Status.LOCKED;
            case CLOSED -> AccountQuery.Status.CLOSED;
        };
    }

    private static String mask(String last4, AccountNumberProtection protection) {
        try {
            return protection.mask(last4);
        } catch (RuntimeException failure) {
            // Fail closed: never emit a partially-masked or raw account number.
            throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
