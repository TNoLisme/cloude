package com.simulatedbank.banking.notification.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable DTO supplied by Transfer caller inside T2.
 */
public record TransferNotice(
        Long customerId,
        Long transferId,
        String transferReference,
        NotificationChannel channel,
        String templateCode,
        Map<String, Object> payloadMasked,
        String correlationId
) {
    public TransferNotice {
        payloadMasked = payloadMasked == null ? Collections.emptyMap() : Collections.unmodifiableMap(Map.copyOf(payloadMasked));
    }
}
