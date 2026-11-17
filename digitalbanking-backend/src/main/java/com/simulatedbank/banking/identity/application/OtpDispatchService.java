package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.notification.api.OtpGateway.Failure;
import com.simulatedbank.banking.notification.api.OtpGateway.Outcome;
import com.simulatedbank.banking.notification.api.OtpGateway.Result;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One synchronous attempt using an ephemeral OTP after the caller's issue transaction commits.
 * NOT_SUPPORTED suspends even an ambient caller transaction before resolving/invoking the
 * gateway. Only the conditional repository acknowledgement opens a short transaction.
 *
 * <p>No retry, secret persistence, lease, or resend is implemented: MOD-OI-07 is open.
 * Gateway adapters must enforce transport deadlines themselves; interrupting an executor
 * cannot guarantee termination or safe lifetime of its borrowed OTP. The caller must wipe
 * its OTP array in a finally block after this method returns or throws.</p>
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class OtpDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(OtpDispatchService.class);
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final Result INVALID_RESPONSE = new Result(Outcome.FAILED, Failure.INVALID_RESPONSE);

    private final ChallengeRepository challenges;
    private final ObjectProvider<OtpGateway> gateways;
    private final TransactionTemplate withoutTransaction;
    private final Clock clock;
    private final MeterRegistry metrics;

    public OtpDispatchService(ChallengeRepository challenges, ObjectProvider<OtpGateway> gateways,
            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry metrics) {
        this.challenges = challenges;
        this.gateways = gateways;
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Does not own the OTP array; only a newly committed CREATED snapshot may be dispatched. */
    public void deliver(ChallengeRepository.ChallengeSnapshot challenge, char[] otp, String correlationId) {
        try {
            withoutTransaction.executeWithoutResult(ignored -> deliverWithoutTransaction(challenge, otp, correlationId));
        } catch (BankingBusinessException failure) {
            throw failure;
        } catch (RuntimeException transactionFailure) {
            // Transaction infrastructure failures must not disclose connection/provider details.
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.AMBIGUOUS));
        }
    }

    private void deliverWithoutTransaction(ChallengeRepository.ChallengeSnapshot challenge, char[] otp,
            String correlationId) {
        if (!"CREATED".equals(challenge.status()) || challenge.consumedAt() != null
                || !challenge.expiresAt().isAfter(clock.instant()) || otp == null || otp.length == 0) {
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.REJECTED));
        }

        OtpGateway gateway;
        try {
            // Resolve one unique/primary candidate, never iterate or fall back to another provider.
            gateway = gateways.getIfUnique();
        } catch (RuntimeException configurationFailure) {
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.UNCONFIGURED));
            return;
        }
        if (gateway == null) {
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.UNCONFIGURED));
        }

        Result result;
        try {
            result = normalize(gateway.sendOtp(challenge.challengeRef(), challenge.userId(), challenge.channel(),
                    otp, challenge.expiresAt(), correlationId));
        } catch (RuntimeException providerFailure) {
            result = classify(providerFailure);
        }
        if (result.outcome() != Outcome.ACCEPTED) {
            reject(challenge, correlationId, result);
        }

        boolean marked;
        try {
            marked = challenges.markSent(challenge.challengeId(), challenge.otpHash());
        } catch (RuntimeException acknowledgementFailure) {
            // Send may already have succeeded; do not retry or expose the persistence exception.
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.AMBIGUOUS));
            return;
        }
        if (!marked) {
            // Expiry, invalidation or a changed verifier won the race. Never acknowledge stale OTP.
            reject(challenge, correlationId, new Result(Outcome.FAILED, Failure.AMBIGUOUS));
        }
        record(challenge, correlationId, result);
    }

    private static Result normalize(Result result) {
        if (result == null || result.outcome() == null || result.failure() == null) {
            return INVALID_RESPONSE;
        }
        if (result.outcome() == Outcome.ACCEPTED) {
            return result.failure() == Failure.NONE ? result : INVALID_RESPONSE;
        }
        if (result.failure() == Failure.NONE) {
            return INVALID_RESPONSE;
        }
        if (result.outcome() == Outcome.RETRYABLE) {
            return switch (result.failure()) {
                case TIMEOUT, UNAVAILABLE, THROTTLED, AMBIGUOUS -> result;
                default -> INVALID_RESPONSE;
            };
        }
        return result;
    }

    private static Result classify(RuntimeException exception) {
        boolean unavailable = false;
        Throwable cause = exception;
        // Bounded traversal tolerates cause cycles and pathological wrapper depth without allocations.
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return new Result(Outcome.RETRYABLE, Failure.TIMEOUT);
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                unavailable = true;
            }
            cause = cause.getCause();
        }
        return unavailable ? new Result(Outcome.RETRYABLE, Failure.UNAVAILABLE)
                : new Result(Outcome.FAILED, Failure.AMBIGUOUS);
    }

    private void reject(ChallengeRepository.ChallengeSnapshot challenge, String correlationId, Result result) {
        record(challenge, correlationId, result);
        throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED);
    }

    private void record(ChallengeRepository.ChallengeSnapshot challenge, String correlationId, Result result) {
        metrics.counter("auth.otp.delivery", "outcome", result.outcome().name(),
                "failure", result.failure().name()).increment();
        LOG.info("OTP_DELIVERY outcome={} failure={} challengeRef={} correlationId={}",
                result.outcome(), result.failure(), challenge.challengeRef(), correlationId);
    }
}
