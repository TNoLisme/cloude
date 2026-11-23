package com.simulatedbank.banking.fraud.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.application.dto.ReviewFraudFlagRequest;
import com.simulatedbank.banking.fraud.application.port.FraudPersistencePort;
import com.simulatedbank.banking.fraud.domain.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service for fraud flag review workflow.
 */
@Service
@ConditionalOnProperty(name = "app.fraud.enabled", havingValue = "true", matchIfMissing = true)
public class FraudReviewService {

    private final FraudPersistencePort repository;
    private final ReviewStateTransition stateTransition;
    private final Clock clock;

    public FraudReviewService(FraudPersistencePort repository, ReviewStateTransition stateTransition, Clock clock) {
        this.repository = repository;
        this.stateTransition = stateTransition;
        this.clock = clock;
    }

    @Transactional
    public FraudFlagItem reviewFlag(long flagId, ReviewDecision decision, String notes, Long reviewerId) {
        if (decision == null) {
            throw new IllegalArgumentException("decision cannot be null");
        }
        if (reviewerId == null || reviewerId <= 0) {
            throw new IllegalArgumentException("Valid reviewerId (> 0) is required");
        }

        FraudFlagItem flag = repository.findFlagForUpdateNowait(flagId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.FRAUD_FLAG_NOT_FOUND, "Fraud flag not found: " + flagId));

        FlagStatus targetStatus = stateTransition.transition(flag.status(), decision, notes);

        Instant reviewedAt = clock.instant();
        boolean updated = repository.updateReview(flagId, targetStatus, decision, notes, reviewerId, reviewedAt);
        if (!updated) {
            throw new BankingBusinessException(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED, "Fraud flag review conflict for flag: " + flagId);
        }

        return repository.findFlagById(flagId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.FRAUD_FLAG_NOT_FOUND, "Fraud flag not found: " + flagId));
    }

    /**
     * Reviews a fraud flag using an untrusted client request DTO and an explicit trusted reviewer ID.
     * The trusted reviewer ID must be provided by the secure caller context (e.g. Identity Principal),
     * never accepted from the client request payload.
     */
    @Transactional
    public FraudFlagItem reviewFlag(long flagId, @Valid ReviewFraudFlagRequest request, Long trustedReviewerId) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        return reviewFlag(flagId, request.decision(), request.notes(), trustedReviewerId);
    }
}
