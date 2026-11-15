package com.simulatedbank.banking.notification.adapter.out;

import com.simulatedbank.banking.notification.domain.NotificationItem;

/**
 * Gateway interface for dispatching notifications to delivery providers or simulators.
 */
public interface SimulatedNotificationGateway {

    void send(NotificationItem item);

    /**
     * Indicates whether this gateway is a test/development simulator rather than a real provider adapter.
     */
    default boolean isSimulator() {
        return true;
    }
}
