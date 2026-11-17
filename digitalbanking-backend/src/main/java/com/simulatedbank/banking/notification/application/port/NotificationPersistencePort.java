package com.simulatedbank.banking.notification.application.port;

import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Outbound persistence port for transactional outbox enqueue, batch claim, and status updates.
 * Implemented by persistence adapters (e.g. NotificationJdbcRepository in adapter/out).
 */
public interface NotificationPersistencePort {

    long insert(NotificationItem item);

    List<NotificationItem> claimBatch(int batchSize, Duration leaseDuration, String workerId);

    boolean markSent(long notificationId, String claimToken, Instant sentAt);

    boolean markRetryOrFailed(long notificationId, String claimToken, NotificationStatus status, Instant nextRetryAt, String lastFailureCode);
}
