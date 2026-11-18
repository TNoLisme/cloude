package com.simulatedbank.banking.notification.adapter.out;

import com.simulatedbank.banking.notification.application.port.NotificationDeliveryPort;
import com.simulatedbank.banking.notification.domain.NotificationItem;

/**
 * Gateway interface for dispatching notifications to delivery simulators.
 * Implements NotificationDeliveryPort as a test/development adapter.
 */
public interface SimulatedNotificationGateway extends NotificationDeliveryPort {

    @Override
    void send(NotificationItem item);

    /**
     * Indicates whether this gateway is a test/development simulator rather than a real provider adapter.
     */
    @Override
    default boolean isSimulator() {
        return true;
    }
}
