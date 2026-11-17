package com.simulatedbank.banking.identity.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expiry-only recovery for abandoned challenges. Never reconstructs or dispatches OTP.
 * Durable redelivery remains blocked by MOD-OI-07's missing approved secret handoff.
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class OtpExpiryRecoveryService {
    private static final int BATCH_SIZE = 100;
    private final ChallengeRepository challenges;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;

    public OtpExpiryRecoveryService(ChallengeRepository challenges,
            PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.challenges = challenges;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meters = meters;
    }

    /** One bounded batch; SKIP LOCKED allows concurrent workers without double transitions. */
    public int recoverExpired() {
        Integer count = transaction.execute(status -> challenges.expireDue(BATCH_SIZE));
        int expired = count == null ? 0 : count;
        meters.counter("auth.otp.recovery", "outcome", "EXPIRED").increment(expired);
        return expired;
    }
}
