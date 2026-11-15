package com.simulatedbank.banking.customer.adapter.in;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.application.CustomerService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1")
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/customers/me")
    public CustomerService.CustomerProfile me(Authentication authentication) {
        return customers.selfProfile(session(authentication));
    }

    @GetMapping("/admin/customers/{customerId}")
    public CustomerService.CustomerProfile staffLookup(Authentication authentication, @PathVariable long customerId) {
        return customers.staffProfile(session(authentication), customerId);
    }

    private static SessionAuthenticator.Session session(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session value)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return value;
    }
}
