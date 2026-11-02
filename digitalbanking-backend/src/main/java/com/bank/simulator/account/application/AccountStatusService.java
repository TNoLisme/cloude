package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class AccountStatusService {
    private final AccountJdbcRepository accounts;
    private final AuditWriter audit;
    private final Clock clock;

    public AccountStatusService(AccountJdbcRepository accounts, AuditWriter audit, Clock clock) {
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AccountJdbcRepository.AccountRow change(AuthenticatedActor actor, UUID accountId, String targetStatus,
                                                   String reason) {
        requireOperator(actor);
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Reason is invalid.");
        }
        AccountJdbcRepository.AccountRow account = accounts.lock(accountId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account is not available.");
        }
        if ("CLOSED".equals(account.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE", "Closed account cannot change status.");
        }
        if (!targetStatus.equals(account.status())) {
            accounts.updateStatus(accountId, targetStatus, clock.instant());
            audit.record(actor.userId(), actor.roles().stream().sorted().findFirst().orElse(null),
                    "ACCOUNT_STATUS_CHANGED", "ACCOUNT", accountId, "SUCCESS", null,
                    "Account status changed from " + account.status() + " to " + targetStatus + ". Reason: " + reason.trim());
        }
        return new AccountJdbcRepository.AccountRow(account.id(), account.customerId(), account.accountNumber(),
                account.accountType(), targetStatus, account.balance(), account.currency(), account.openedAt());
    }

    private void requireOperator(AuthenticatedActor actor) {
        if (!actor.hasRole("OPERATOR") && !actor.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
    }
}
