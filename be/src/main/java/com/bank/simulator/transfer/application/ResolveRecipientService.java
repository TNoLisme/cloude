package com.bank.simulator.transfer.application;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.api.AccountModuleApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ResolveRecipientService {
    private final AccountModuleApi accounts;

    public ResolveRecipientService(AccountModuleApi accounts) {
        this.accounts = accounts;
    }

    public Recipient resolve(AuthenticatedActor actor, String accountNumber) {
        if (!actor.hasRole("CUSTOMER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
        AccountModuleApi.AccountView account = accounts.findByAccountNumber(accountNumber);
        if (account == null || !"ACTIVE".equals(account.status()) || !"VND".equals(account.currency())
                || account.ownerUserId() == null || account.ownerUserId().equals(actor.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_AVAILABLE", "The recipient account could not be resolved.");
        }
        return new Recipient(account.accountId(), account.accountNumberMasked(), account.recipientDisplayName(),
                account.currency());
    }

    public record Recipient(UUID accountId, String accountNumberMasked, String recipientDisplayName, String currency) {}
}
