package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.notification.adapter.out.NotificationJdbcRepository;
import com.simulatedbank.banking.notification.adapter.out.SimulatedNotificationGateway;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationRetryPolicy;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Worker component responsible for claiming outbox rows using FOR UPDATE SKIP LOCKED
 * and delivering them to external providers outside the database transaction.
 */
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);

    private final NotificationJdbcRepository repository;
    private final SimulatedNotificationGateway gateway;
    private final NotificationRetryPolicy retryPolicy;
    private final TransactionTemplate requiresNewTemplate;
    private final TransactionTemplate notSupportedTemplate;
    private final Clock clock;

    public NotificationOutboxWorker(
            NotificationJdbcRepository repository,
            SimulatedNotificationGateway gateway,
            NotificationRetryPolicy retryPolicy,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.repository = repository;
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
        this.clock = clock;

        PlatformTransactionManager tm = transactionTemplate.getTransactionManager();
        if (tm != null) {
            TransactionTemplate rn = new TransactionTemplate(tm);
            rn.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTemplate = rn;

            TransactionTemplate ns = new TransactionTemplate(tm);
            ns.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            this.notSupportedTemplate = ns;
        } else {
            this.requiresNewTemplate = transactionTemplate;
            this.notSupportedTemplate = transactionTemplate;
        }
    }

    /**
     * Polls and processes one batch of notifications.
     *
     * @param batchSize maximum number of records to claim
     * @return number of records claimed and attempted
     */
    public int processBatch(int batchSize) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("NotificationOutboxWorker must not run inside an active transaction");
        }

        String claimToken = UUID.randomUUID().toString();

        // Step 1: Claim batch in an isolated, immediately committed database transaction
        List<NotificationItem> claimed = requiresNewTemplate.execute(status ->
                repository.claimBatch(batchSize, retryPolicy.leaseDuration(), claimToken)
        );

        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }

        // Step 2 & 3: Deliver each notification outside DB transaction and update outcome conditionally
        for (NotificationItem item : claimed) {
            deliverItem(item, claimToken);
        }

        return claimed.size();
    }

    public boolean deliverItem(NotificationItem item, String claimToken) {
        if (item.retryCount() > retryPolicy.maxAttempts()) {
            log.warn("Notification {} exceeded maxAttempts ({}) due to repeated crashes/reclaims; marking FAILED without sending",
                    item.notificationId(), retryPolicy.maxAttempts());
            boolean updated = Boolean.TRUE.equals(requiresNewTemplate.execute(status ->
                    repository.markRetryOrFailed(
                            item.notificationId(),
                            claimToken,
                            NotificationStatus.FAILED,
                            null,
                            retryPolicy.exhaustedFailureCode()
                    )
            ));
            return false;
        }

        try {
            if (gateway.isSimulator()) {
                log.info("Dispatching notificationId={} via SIMULATOR gateway (simulated dispatch)", item.notificationId());
            }
            // Network dispatch guaranteed outside database transaction (suspends active transaction if caller is transactional)
            notSupportedTemplate.executeWithoutResult(status -> gateway.send(item));

            // Conditional update on success in an isolated new transaction
            Instant now = clock.instant();
            boolean updated = Boolean.TRUE.equals(requiresNewTemplate.execute(status ->
                    repository.markSent(item.notificationId(), claimToken, now)
            ));

            if (!updated) {
                log.warn("Stale worker completion rejected for notificationId={}", item.notificationId());
            }
            return updated;
        } catch (Exception ex) {
            // If simulating sudden worker/process crash, do not perform any DB update
            if (ex instanceof RuntimeException re && (ex.getClass().getSimpleName().contains("Crash")
                    || (ex.getMessage() != null && ex.getMessage().contains("crash")))) {
                throw re;
            }

            String safeCode = retryPolicy.toSafeFailureCode(ex);
            int newRetryCount = item.retryCount();
            NotificationStatus nextStatus = retryPolicy.determineNextStatus(newRetryCount);
            if (nextStatus == NotificationStatus.FAILED) {
                safeCode = retryPolicy.exhaustedFailureCode();
            }
            Instant nextRetryAt = retryPolicy.calculateNextRetry(clock.instant(), newRetryCount);

            final String finalFailureCode = safeCode;
            boolean updated = Boolean.TRUE.equals(requiresNewTemplate.execute(status ->
                    repository.markRetryOrFailed(item.notificationId(), claimToken, nextStatus, nextRetryAt, finalFailureCode)
            ));

            if (!updated) {
                log.warn("Stale worker failure update rejected for notificationId={}", item.notificationId());
            }
            return false;
        }
    }
}
