package com.simulatedbank.banking.fraud.application.port;

import com.simulatedbank.banking.fraud.domain.FlagStatus;
import com.simulatedbank.banking.fraud.domain.FraudFlagItem;
import com.simulatedbank.banking.fraud.domain.ReviewDecision;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbound persistence port for fraud flag review and retrieval operations.
 * Implemented by persistence adapters (e.g. FraudJdbcRepository in adapter/out).
 */
public interface FraudPersistencePort {

    Optional<FraudFlagItem> findFlagForUpdateNowait(long flagId);

    boolean updateReview(long flagId, FlagStatus status, ReviewDecision decision, String notes, Long reviewerId, Instant reviewedAt);

    Optional<FraudFlagItem> findFlagById(long flagId);
}
