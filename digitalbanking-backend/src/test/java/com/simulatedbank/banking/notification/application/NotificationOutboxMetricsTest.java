package com.simulatedbank.banking.notification.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOutboxMetricsTest {

    @Test
    void recordsOnlyFixedCardinalitySafeOutboxMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new NotificationOutboxMetrics(registry);

        metrics.recordClaimed(3);
        metrics.recordDelivery("sent");
        metrics.recordDelivery("failed");

        assertThat(registry.get("banking.notification.outbox.claimed").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("banking.notification.outbox.delivery")
                .tag("outcome", "sent").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("banking.notification.outbox.delivery")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getValue()).doesNotContain("@", "12345678", "token")));
    }
}
