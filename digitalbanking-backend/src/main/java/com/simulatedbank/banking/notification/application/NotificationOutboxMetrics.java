package com.simulatedbank.banking.notification.application;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.Set;

/** Fixed-cardinality metrics for notification outbox processing. */
public final class NotificationOutboxMetrics {
    private static final Set<String> OUTCOMES = Set.of("sent", "failed", "stale");
    private final MeterRegistry registry;

    public NotificationOutboxMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
    }

    public void recordClaimed(int count) {
        if (count > 0) registry.counter("banking.notification.outbox.claimed").increment(count);
    }

    public void recordTerminalFailure() {
        registry.counter("banking.notification.outbox.terminal.failure").increment();
    }

    public void recordDelivery(String outcome) {
        if (!OUTCOMES.contains(outcome)) throw new IllegalArgumentException("Unsupported notification delivery outcome");
        registry.counter("banking.notification.outbox.delivery", "outcome", outcome).increment();
    }
}
