package com.simulatedbank.banking.fraud.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.adapter.out.FraudJdbcRepository;
import com.simulatedbank.banking.fraud.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service for fraud flag review workflow.
 */
@Service
public class FraudReviewService {

    private final FraudJdbcRepository repository;
    private final ReviewStateTransition stateTransition;
    private final Clock clock;

    public FraudReviewService(FraudJdbcRepository repository, ReviewStateTransition stateTransition, Clock clock) {
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
}
