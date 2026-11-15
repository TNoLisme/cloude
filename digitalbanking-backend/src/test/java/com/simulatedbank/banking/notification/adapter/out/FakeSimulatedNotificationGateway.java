package com.simulatedbank.banking.notification.adapter.out;

import com.simulatedbank.banking.notification.domain.NotificationItem;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeSimulatedNotificationGateway implements SimulatedNotificationGateway {

    public enum Behavior {
        SUCCESS,
        FAIL_TEMPORARY,
        TIMEOUT,
        SUCCESS_THEN_CRASH
    }

    private volatile Behavior behavior = Behavior.SUCCESS;
    private final Map<String, AtomicInteger> deliveryCounts = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<NotificationItem> onSendListener;

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    public void setOnSendListener(java.util.function.Consumer<NotificationItem> onSendListener) {
        this.onSendListener = onSendListener;
    }

    public int getDeliveryCount(String reference) {
        AtomicInteger count = deliveryCounts.get(reference);
        return count == null ? 0 : count.get();
    }

    @Override
    public void send(NotificationItem item) {
        String ref = item.payloadMasked().get("reference") != null
                ? item.payloadMasked().get("reference").toString()
                : String.valueOf(item.notificationId());

        if (onSendListener != null) {
            onSendListener.accept(item);
        }

        switch (behavior) {
            case SUCCESS -> {
                deliveryCounts.computeIfAbsent(ref, k -> new AtomicInteger()).incrementAndGet();
            }
            case FAIL_TEMPORARY -> {
                throw new IllegalStateException("Simulated transient network error contacting SMS gateway");
            }
            case TIMEOUT -> {
                throw new RuntimeException("Simulated connection timed out to notification provider");
            }
            case SUCCESS_THEN_CRASH -> {
                // Sent to external provider successfully...
                deliveryCounts.computeIfAbsent(ref, k -> new AtomicInteger()).incrementAndGet();
                // ...but worker process crashed immediately before committing sent_at to DB!
                throw new SimulatedCrashException("Simulated JVM crash / power loss before DB status update");
            }
        }
    }

    public static class SimulatedCrashException extends RuntimeException {
        public SimulatedCrashException(String message) {
            super(message);
        }
    }
}
