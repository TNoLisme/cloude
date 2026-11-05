package com.bank.simulator.transfer.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.application.ResolveRecipientService;
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
    private final ResolveRecipientService resolveRecipient;

    public RecipientController(ResolveRecipientService resolveRecipient) {
        this.resolveRecipient = resolveRecipient;
    }

    @PostMapping("/recipients/resolve")
    public RecipientConfirmation resolve(@Valid @RequestBody ResolveRecipientRequest request) {
        var recipient = resolveRecipient.resolve(actor(), request.accountNumber());
        return new RecipientConfirmation(recipient.accountId(), recipient.accountNumberMasked(),
                recipient.recipientDisplayName(), recipient.currency());
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
