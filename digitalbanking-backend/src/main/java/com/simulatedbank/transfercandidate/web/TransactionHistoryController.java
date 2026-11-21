package com.simulatedbank.transfercandidate.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.config.ApiPage;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.application.SecurityDenyAudit;
import com.simulatedbank.banking.transfer.application.TransactionHistory;
import com.simulatedbank.banking.transfer.application.TransactionHistory.Direction;
import com.simulatedbank.banking.transfer.application.TransferActor;
import com.simulatedbank.banking.transfer.application.TransferQueryService;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionHistoryController {
    private final TransferQueryService receipts;
    private final TransactionHistory history;
    private final AccountQuery accounts;
    private final Clock clock;
    private final SecurityDenyAudit denyAudit;

    public TransactionHistoryController(TransferQueryService receipts, TransactionHistory history,
            AccountQuery accounts, Clock clock, SecurityDenyAudit denyAudit) {
        this.receipts = receipts;
        this.history = history;
        this.accounts = accounts;
        this.clock = clock;
        this.denyAudit = denyAudit;
    }

    @GetMapping
    public ApiPage<TransactionHistory.Item> list(Principal principal,
            @RequestParam(required = false) Long customerId, @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) TransferStatus status, @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String minAmount, @RequestParam(required = false) String maxAmount,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        var actor = TransferController.actor(principal);
        if ((customerId != null && customerId <= 0) || (accountId != null && accountId <= 0)
                || (direction != null && accountId == null)) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Long scope = customerScope(actor, customerId);
        if (actor.hasAuthority("ROLE_CUSTOMER") && accountId != null) {
            accounts.assertOwnership(accountId, scope, actor.correlationId());
        }
        BigDecimal minimum = money(minAmount);
        BigDecimal maximum = money(maxAmount);
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return history.list(new TransactionHistory.Filter(scope, accountId, status, direction,
                minimum, maximum, !actor.hasAuthority("ROLE_CUSTOMER"), sort, ApiPage.Window.resolve(from, to, page, size, clock.instant())));
    }

    @GetMapping("/{reference}")
    public TransactionDetail get(Principal principal, @PathVariable String reference) {
        if (!reference.matches(TransferController.REFERENCE)) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var actor = TransferController.actor(principal);
        Long customer = customerScope(actor, null);
        var view = receipts.getReceipt(reference, actor);
        boolean sender = customer != null && owns(customer, view.source().accountId());
        boolean recipient = customer != null && owns(customer, view.destination().accountId());
        Direction direction = recipient ? (sender ? Direction.INTERNAL : Direction.CREDIT) : Direction.DEBIT;
        var counterparty = direction == Direction.CREDIT ? view.source() : view.destination();
        return new TransactionDetail(TransferController.response(view), direction,
                TransactionHistory.Counterparty.withLast4(counterparty.last4()));
    }

    private boolean owns(long customerId, long accountId) {
        return accounts.findAccount(accountId).map(account -> account.customerId() == customerId).orElse(false);
    }

    private Long customerScope(TransferActor actor, Long requested) {
        if (actor.hasAuthority("ROLE_CUSTOMER")) {
            if (!actor.hasAuthority("TRANSACTION_READ_SELF") || actor.customerId() == null
                    || (requested != null && !requested.equals(actor.customerId()))) {
                denyAudit.accessDenied(actor, "CUSTOMER", requested == null ? "UNKNOWN" : requested.toString(),
                        "SCOPE_DENIED", "TRANSACTION_READ_SELF");
                throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            }
            return actor.customerId();
        }
        if (!actor.hasAuthority("TRANSACTION_READ") || !(actor.hasAuthority("ROLE_OPERATIONS")
                || actor.hasAuthority("ROLE_ADMIN") || actor.hasAuthority("ROLE_AUDITOR"))) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return requested;
    }

    private static BigDecimal money(String value) {
        if (value == null) return null;
        if (!value.matches("(0|[1-9][0-9]{0,16})\\.[0-9]{2}")) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return new BigDecimal(value);
    }

    public record TransactionDetail(@JsonUnwrapped TransferResponse receipt,
                                    Direction direction, TransactionHistory.Counterparty counterparty) {}
}
