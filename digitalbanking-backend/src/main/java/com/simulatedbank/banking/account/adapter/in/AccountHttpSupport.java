package com.simulatedbank.banking.account.adapter.in;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Parse/response shaping dùng chung cho các inbound adapter read-only của Account
 * ({@code AccountController} cho `API-ACC-01/02`, {@code AdminCustomerAccountsController} cho
 * `API-ADM-02`).
 *
 * <p>Fail-closed: ID theo schema `ID` `[1-9][0-9]{0,18}`; status ngoài enum đã duyệt; money luôn
 * chuỗi scale 2 (OpenAPI `Money`). Principal chỉ lấy từ attribute snapshot do
 * {@code SessionAuthenticationFilter} gắn — không decode token, không nhận id/role/scope từ
 * client. Controller không tự dựng problem body (Common `GlobalExceptionHandler`).</p>
 */
final class AccountHttpSupport {

    /** OpenAPI schema `ID`: `[1-9][0-9]{0,18}` (BIGINT dương, tối đa 19 chữ số). */
    private static final String ID_PATTERN = "[1-9][0-9]{0,18}";

    private AccountHttpSupport() {
    }

    /** ID path/query theo schema `ID`; sai định dạng hoặc vượt LONG_MAX → 400 trước persistence. */
    static long parseId(String rawId) {
        if (rawId == null || !rawId.matches(ID_PATTERN)) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException tooLarge) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Query `status` tùy chọn đúng enum đã duyệt; giá trị khác (kể cả rỗng) → 400. */
    static AccountQuery.Status status(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        try {
            return AccountQuery.Status.valueOf(rawStatus);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static AccountSummaryResponse toSummary(AccountQuery.AccountView view) {
        return new AccountSummaryResponse(
                Long.toString(view.accountId()),
                view.accountNumberMasked(),
                view.accountNumberLast4(),
                view.currency(),
                view.status().name(),
                money(view.availableBalance()),
                money(view.ledgerBalance()),
                view.asOf());
    }

    /** Money ra HTTP luôn là chuỗi scale 2 (OpenAPI `Money`); lỗi shape là internal, không đoán. */
    static String money(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException invalid) {
            throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Principal từ `SessionAuthenticationFilter` (attribute session snapshot, như AuthController). */
    static SessionAuthenticator.Session principal(HttpServletRequest request) {
        Object session = request.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        if (session instanceof SessionAuthenticator.Session snapshot) {
            return snapshot;
        }
        throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
    }

    static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute("correlationId");
        return value instanceof String text && !text.isEmpty() ? text : UUID.randomUUID().toString();
    }
}
