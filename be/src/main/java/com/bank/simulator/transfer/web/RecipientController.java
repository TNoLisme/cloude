package com.bank.simulator.transfer.web;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.application.TransferPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RecipientController {
    private final AccountJdbcRepository accounts;
    private final com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository identities;

    public RecipientController(AccountJdbcRepository accounts,
                               com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository identities) {
        this.accounts = accounts;
        this.identities = identities;
    }

    @PostMapping("/recipients/resolve")
    public RecipientConfirmation resolve(@Valid @RequestBody ResolveRecipientRequest request) {
        AuthenticatedActor actor = actor();
        if (!actor.hasRole("CUSTOMER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
        AccountJdbcRepository.AccountRow account = accounts.findByAccountNumber(request.accountNumber());
        if (account == null || !"ACTIVE".equals(account.status()) || !"VND".equals(account.currency())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_AVAILABLE", "Recipient is not available.");
        }
        var customer = identities.findCustomerById(account.customerId());
        if (customer == null || customer.userId().equals(actor.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_AVAILABLE", "Recipient is not available.");
        }
        String displayName = customer.fullName().isBlank() ? "Customer" : customer.fullName();
        return new RecipientConfirmation(account.id(), account.maskedNumber(), displayName, account.currency());
    }

    private AuthenticatedActor actor() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedActor actor) return actor;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
    }

    public record ResolveRecipientRequest(@NotBlank @Size(min = 8, max = 34)
                                          @Pattern(regexp = "^[0-9]+$") String accountNumber) {}
    public record RecipientConfirmation(UUID accountId, String accountNumberMasked, String recipientDisplayName,
                                        String currency) {}
}
