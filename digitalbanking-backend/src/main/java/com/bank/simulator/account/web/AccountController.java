package com.bank.simulator.account.web;

import com.bank.simulator.account.application.AccountOperatorService;
import com.bank.simulator.account.application.AccountQueryService;
import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {
    private final AccountQueryService queries;
    private final AccountOperatorService operator;
    private final com.bank.simulator.account.application.AccountStatusService statuses;

    public AccountController(AccountQueryService queries, AccountOperatorService operator,
                             com.bank.simulator.account.application.AccountStatusService statuses) {
        this.queries = queries;
        this.operator = operator;
        this.statuses = statuses;
    }

    @GetMapping("/accounts")
    public AccountPage myAccounts() {
        List<AccountResponse> items = queries.myAccounts(actor()).stream().map(AccountResponse::from).toList();
        return new AccountPage(items, null);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        return AccountResponse.from(queries.get(actor(), accountId));
    }

    @PostMapping("/operator/accounts/{accountId}/seed-balance")
    public ResponseEntity<SeedBalanceResponse> seedBalance(@PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SeedBalanceRequest request) {
        var result = operator.seed(actor(), accountId,
                new com.bank.simulator.account.application.SeedBalanceCommand(request.amount(), request.currency(), request.reference()),
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(new SeedBalanceResponse(result.seedTransactionId(), result.accountId(), result.amount(),
                        result.currency(), result.balanceAfter(), result.createdAt()));
    }

    @PostMapping("/operator/accounts/{accountId}/block")
    public AccountResponse block(@PathVariable UUID accountId, @Valid @RequestBody AccountStatusRequest request) {
        return AccountResponse.from(statuses.change(actor(), accountId, "BLOCKED", request.reason()));
    }

    @PostMapping("/operator/accounts/{accountId}/unblock")
    public AccountResponse unblock(@PathVariable UUID accountId, @Valid @RequestBody AccountStatusRequest request) {
        return AccountResponse.from(statuses.change(actor(), accountId, "ACTIVE", request.reason()));
    }

    private AuthenticatedActor actor() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedActor authenticatedActor) return authenticatedActor;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
    }

    public record AccountPage(List<AccountResponse> items, String nextCursor) {}
    public record AccountResponse(UUID accountId, String accountNumberMasked, String accountType, String status,
                                  String balance, String currency, Instant openedAt) {
        static AccountResponse from(AccountJdbcRepository.AccountRow row) {
            return new AccountResponse(row.id(), row.maskedNumber(), row.accountType(), row.status(), row.balance(),
                    row.currency(), row.openedAt());
        }
    }
    public record OperatorCustomerResponse(UUID customerId, String fullName, String phone, String email,
            boolean isPinSet, Instant createdAt, List<AccountResponse> accounts) {}
    public record SeedBalanceRequest(@NotBlank @Pattern(regexp = "(?:[1-9][0-9]{0,7}|100000000)") String amount,
                                     @Pattern(regexp = "VND") String currency,
                                     @NotBlank @Size(max = 100) String reference) {}
    public record SeedBalanceResponse(UUID seedTransactionId, UUID accountId, String amount, String currency,
                                      String balanceAfter, Instant createdAt) {}
    public record AccountStatusRequest(@NotBlank @Size(max = 500) String reason) {}
}
