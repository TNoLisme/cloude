package com.simulatedbank.banking.account.adapter.in;

import com.simulatedbank.banking.account.application.AccountService;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/accounts")
    public AccountService.AccountsResponse list(Authentication authentication,
            @RequestParam(required = false) String status) {
        return accounts.listOwn(session(authentication), status);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountService.AccountDetail detail(Authentication authentication, @PathVariable long accountId) {
        return accounts.detail(session(authentication), accountId);
    }

    @GetMapping("/admin/customers/{customerId}/accounts")
    public AccountService.AccountsResponse staffList(Authentication authentication, @PathVariable long customerId) {
        return accounts.listForStaff(session(authentication), customerId);
    }

    private static SessionAuthenticator.Session session(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session value)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return value;
    }
}
