package com.simulatedbank.banking.fraud.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;

import java.util.Locale;
import java.util.Set;

/**
 * Encapsulates fraud flag review state transitions per 04 §4.7.2 and 07 §8.7.2.
 */
public class ReviewStateTransition {

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "otp",
            "password",
            "pin",
            "token",
            "secret"
    );

    public FlagStatus transition(FlagStatus currentStatus, ReviewDecision decision, String notes) {
        if (currentStatus == null) {
            throw new IllegalArgumentException("currentStatus cannot be null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision cannot be null");
        }

        if (currentStatus.isTerminal()) {
            throw new BankingBusinessException(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED,
                    "Cannot review flag already in terminal state: " + currentStatus);
        }

        if (notes != null) {
            if (notes.length() > 500) {
                throw new IllegalArgumentException("Review notes length (" + notes.length() + ") exceeds maximum limit of 500");
            }
            String lower = notes.toLowerCase(Locale.ROOT);
            for (String kw : SENSITIVE_KEYWORDS) {
                if (lower.contains(kw)) {
                    throw new IllegalArgumentException("Sensitive credential keyword '" + kw + "' detected in review notes");
                }
            }
        }

        return decision.toTargetStatus();
    }
}
