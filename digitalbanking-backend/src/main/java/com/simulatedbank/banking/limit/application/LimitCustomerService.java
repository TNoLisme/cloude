package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitCustomerService {
    private final LimitReadPort repository;
    private final ObjectProvider<AccountQuery> accounts;
    private final LimitSecurityDenialReporter denialReporter;
    private final Clock clock;
    private final ZoneId businessZoneId;

    @Transactional(readOnly = true)
    public LimitsResult getLimits(Authentication authentication, String requestedAccountId, String requestedCustomerId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        if (session.customerId() == null || !session.authorities().contains("ROLE_CUSTOMER")
                || !session.authorities().contains("LIMIT_READ_SELF")) {
            denialReporter.report(authentication, "/api/v1/limits", "MISSING_PERMISSION_OR_ROLE");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        long customerId = id(session.customerId());
        if (requestedCustomerId != null && id(requestedCustomerId) != customerId) {
            denialReporter.report(authentication, "/api/v1/limits", "CUSTOMER_SCOPE");
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        Long accountId = requestedAccountId == null ? null : id(requestedAccountId);
        if (accountId != null) {
            var provider = accounts.getIfAvailable();
            if (provider == null) throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            var account = provider.findAccount(accountId)
                    .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            if (account.accountId() != accountId || account.customerId() != customerId) {
                denialReporter.report(authentication, "/api/v1/limits", "ACCOUNT_SCOPE", accountId.toString());
                throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            }
        }
        var asOf = clock.instant();
        var usageDate = asOf.atZone(businessZoneId).toLocalDate();
        var rows = repository.findSnapshot(customerId, accountId, usageDate, asOf.truncatedTo(ChronoUnit.MICROS));
        var perTransaction = rows.stream().map(LimitReadPort.Row::perTransactionLimit)
                .min(BigDecimal::compareTo).orElse(null);
        var remaining = rows.stream().map(row -> row.dailyLimit().subtract(row.usedToday()).max(BigDecimal.ZERO))
                .min(BigDecimal::compareTo).orElse(null);
        return new LimitsResult(customerId, "VND", usageDate, perTransaction, remaining, rows, asOf);
    }

    private static long id(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
