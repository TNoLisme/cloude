package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.ratelimit.RateLimitInterceptor;
import com.bank.simulator.shared.ratelimit.RateLimitPolicyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountQueryService {
    private final AccountJdbcRepository accounts;
    private final IdentityJdbcRepository identities;
    private final AuditWriter audit;
    private final RateLimitInterceptor rateLimiter;
    private final RateLimitPolicyFactory policies;

    public AccountQueryService(AccountJdbcRepository accounts, IdentityJdbcRepository identities, AuditWriter audit,
                               RateLimitInterceptor rateLimiter, RateLimitPolicyFactory policies) {
        this.accounts = accounts;
        this.identities = identities;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.policies = policies;
    }

    public List<AccountJdbcRepository.AccountRow> myAccounts(AuthenticatedActor actor) {
        IdentityJdbcRepository.CustomerRecord customer = identities.findCustomerByUserId(actor.userId());
        if (customer == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Customer account access is unavailable.");
        }
        return accounts.findByCustomer(customer.customerId());
    }

    public AccountJdbcRepository.AccountRow get(AuthenticatedActor actor, UUID accountId) {
        AccountJdbcRepository.AccountRow account = accounts.find(accountId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account is not available.");
        }
        IdentityJdbcRepository.CustomerRecord owner = identities.findCustomerByUserId(actor.userId());
        if (owner == null || !owner.customerId().equals(account.customerId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Account access is forbidden.");
        }
        return account;
    }

    public OperatorCustomerView lookup(AuthenticatedActor actor, String phone, String email) {
        requireOperator(actor);
        if ((phone == null) == (email == null)
                || phone != null && !phone.matches("0[3-9][0-9]{8}")
                || email != null && !email.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Exactly one valid customer filter is required.");
        }
        rateLimiter.check(policies.actorKey("operator-lookup", actor.userId()), policies.policy("operator-lookup"));
        List<IdentityJdbcRepository.CustomerRecord> rows = identities.findByPhoneOrEmail(phone, email);
        if (rows.isEmpty()) {
            audit.record(actor.userId(), firstRole(actor), "CUSTOMER_LOOKUP", "CUSTOMER", null,
                    "FAILURE", null, "Customer lookup returned no result.");
            throw new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer is not available.");
        }
        IdentityJdbcRepository.CustomerRecord customer = rows.getFirst();
        audit.record(actor.userId(), firstRole(actor), "CUSTOMER_LOOKUP", "CUSTOMER", customer.customerId(),
                "SUCCESS", null, "Customer looked up at counter.");
        return new OperatorCustomerView(customer.customerId(), customer.fullName(), customer.phone(), customer.email(),
                customer.pinSet(), customer.createdAt(), accounts.findByCustomer(customer.customerId()));
    }

    private void requireOperator(AuthenticatedActor actor) {
        if (!actor.hasRole("OPERATOR") && !actor.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
    }

    private String firstRole(AuthenticatedActor actor) {
        return actor.roles().stream().sorted().findFirst().orElse(null);
    }

    public record OperatorCustomerView(UUID customerId, String fullName, String phone, String email,
                                       boolean isPinSet, java.time.Instant createdAt,
                                       List<AccountJdbcRepository.AccountRow> accounts) {
    }
}
