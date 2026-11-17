package com.simulatedbank.banking.fraud.application.dto;

import com.simulatedbank.banking.fraud.domain.ReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO representing untrusted client payload for reviewing a fraud flag.
 * Aligned with OpenAPI specification FraudReviewRequest (only decision and notes).
 * <p>
 * Security Notice:
 * Reviewer identity is deliberately excluded from this DTO to prevent privilege escalation.
 * Trusted reviewerId MUST be supplied by authenticated caller context (e.g. Identity Principal).
 */
public record ReviewFraudFlagRequest(
        @NotNull(message = "Review decision is required")
        ReviewDecision decision,

        @Size(max = 500, message = "Notes must not exceed 500 characters")
        String notes
) {}
