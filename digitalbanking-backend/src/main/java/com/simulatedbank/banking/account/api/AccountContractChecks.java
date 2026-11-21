package com.simulatedbank.banking.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Contract guards dùng chung cho {@code account.api} (BE-ACC-01).
 *
 * <p>Không phải API public: class package-private, chỉ bảo vệ các bất biến đã duyệt của DTO nội bộ
 * — mask đúng OpenAPI {@code AccountNumberMasked} pattern, tiền VND scale ≤ 2, ID dương, trace ID
 * hợp lệ — để lỗi lập trình không thể biến thành response/log chứa số tài khoản đầy đủ hoặc số dư
 * sai định dạng. Không chứa business rule của state machine/balance (thuộc domain/persistence).</p>
 */
final class AccountContractChecks {

    private static final Pattern MASKED_ACCOUNT_NUMBER = Pattern.compile("^\\*{4} \\*{4} [0-9]{4}$");
    private static final Pattern LAST4 = Pattern.compile("^[0-9]{4}$");
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final int MONEY_SCALE = 2;
    private static final int MONEY_INTEGER_DIGITS = 17;

    private AccountContractChecks() {
    }

    static void requireId(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static void requireStatus(AccountQuery.Status status) {
        Objects.requireNonNull(status, "status is required");
    }

    static void requireVnd(String currency) {
        if (!"VND".equals(currency)) {
            throw new IllegalArgumentException("currency must be VND");
        }
    }

    static void requireMaskedNumber(String masked, String last4) {
        Objects.requireNonNull(masked, "accountNumberMasked is required");
        Objects.requireNonNull(last4, "accountNumberLast4 is required");
        if (!MASKED_ACCOUNT_NUMBER.matcher(masked).matches() || !LAST4.matcher(last4).matches()
                || !masked.endsWith(last4)) {
            throw new IllegalArgumentException("account number must be masked as **** **** <last4>");
        }
    }

    static void requireInstant(Instant value, String name) {
        Objects.requireNonNull(value, name + " is required");
    }

    static void requireBalance(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        requireMoneyShape(value, name);
    }

    static void requireCorrelationId(String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (!CORRELATION_ID.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("correlationId must match 07 §2 trace pattern");
        }
    }

    private static void requireMoneyShape(BigDecimal value, String name) {
        if (value.scale() > MONEY_SCALE || value.precision() - value.scale() > MONEY_INTEGER_DIGITS) {
            throw new IllegalArgumentException(name + " must fit NUMERIC(19,2)");
        }
    }
}
