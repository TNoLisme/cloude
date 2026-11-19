package com.simulatedbank.banking.notification.application.port;

import com.simulatedbank.banking.notification.domain.NotificationItem;

/**
 * Outbound delivery port for dispatching notifications to external delivery channels or simulators.
 * Implemented by delivery adapters (e.g. SimulatedNotificationGateway in adapter/out).
 */
public interface NotificationDeliveryPort {

    void send(NotificationItem item);

    final class PermanentFailure extends RuntimeException {
        public PermanentFailure(String safeCode) {
            super(safeCode);
        }
    }

    /**
     * Indicates whether this delivery provider is a test/development simulator
     * rather than a real external provider adapter.
     */
    default boolean isSimulator() {
        return false;
    }
}
