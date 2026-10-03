package com.bank.simulator.transfer.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.transfer.application.TransferQueryService;
import com.bank.simulator.transfer.application.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class TransferController {
    private final TransferService transfers;
    private final TransferQueryService queries;

    public TransferController(TransferService transfers, TransferQueryService queries) {
        this.transfers = transfers;
        this.queries = queries;
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreateTransferRequest request) {
        var result = transfers.create(actor(), new TransferService.CreateCommand(request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), request.currency(), request.pin(), request.memo()), idempotencyKey);
        if (result.challengeRequired()) {
            return ResponseEntity.ok().header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(new TransferChallengeResponse(result.transferId(), result.status(), result.message(),
                            result.expiresAt(), result.expiresInSeconds()));
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result);
    }

    @GetMapping("/transfers")
    public TransferPage list(@RequestParam(required = false) Integer limit,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 100 || cursor != null || from != null || to != null
                || status != null && !List.of("AWAITING_OTP", "COMPLETED", "EXPIRED", "FAILED").contains(status)) {
            throw new com.bank.simulator.shared.error.ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Transfer query is invalid.");
        }
        List<TransferQueryService.TransferView> items = queries.list(actor()).stream()
                .filter(item -> status == null || status.equals(item.status())).limit(pageSize).toList();
        return new TransferPage(items.stream().map(TransferListItem::from).toList(), null);
    }

    @GetMapping("/transfers/{transferId}")
    public TransferQueryService.TransferView get(@PathVariable UUID transferId) {
        return queries.get(actor(), transferId);
    }

    @PostMapping("/transfers/{transferId}/confirm-otp")
    public ResponseEntity<TransferQueryService.TransferView> confirm(@PathVariable UUID transferId,
            @Valid @RequestBody ConfirmTransferOtpRequest request) {
        boolean replay = transfers.confirm(actor(), transferId, request.otp());
        return ResponseEntity.status(replay ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(replay))
                .body(queries.get(actor(), transferId));
    }

    private AuthenticatedActor actor() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedActor actor) return actor;
        throw new com.bank.simulator.shared.error.ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
    }

    public record CreateTransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
                                        @NotBlank @Pattern(regexp = "(?:[2-9][0-9]{3}|[1-9][0-9]{4,6}|10000000)") String amount,
                                        @Pattern(regexp = "VND") String currency,
                                        @NotBlank @Pattern(regexp = "[0-9]{6}") String pin,
                                        @Size(max = 140) String memo) {}
    public record ConfirmTransferOtpRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String otp) {}
    public record TransferChallengeResponse(UUID transferId, String status, String message, Instant expiresAt,
                                            int expiresInSeconds) {}
    public record TransferPage(List<TransferListItem> items, String nextCursor) {}
    public record TransferListItem(UUID transferId, String direction, String status, String counterpartyAccountMasked,
            String counterpartyDisplayName, String amount, String currency, String memo, Instant createdAt, Instant completedAt) {
        static TransferListItem from(TransferQueryService.TransferView view) {
            return new TransferListItem(view.transferId(), "OUTGOING", view.status(), "••••", "Customer",
                    view.amount(), view.currency(), view.memo(), view.createdAt(), view.completedAt());
        }
    }
}
