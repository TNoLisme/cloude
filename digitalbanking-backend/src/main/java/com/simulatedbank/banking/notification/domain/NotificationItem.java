package com.simulatedbank.banking.notification.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable domain representation of a notification outbox record.
 */
public record NotificationItem(
        Long notificationId,
        Long customerId,
        Long transferId,
        Long authChallengeId,
        NotificationType notificationType,
        NotificationChannel channel,
        String templateCode,
        Map<String, Object> payloadMasked,
        NotificationStatus status,
        int retryCount,
        Instant nextRetryAt,
        Instant sentAt,
        String failureCode,
        String correlationId,
        String claimToken,
        Instant lockedUntil,
        long claimVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public NotificationItem {
        payloadMasked = payloadMasked == null ? Collections.emptyMap() : Collections.unmodifiableMap(Map.copyOf(payloadMasked));
    }
}
