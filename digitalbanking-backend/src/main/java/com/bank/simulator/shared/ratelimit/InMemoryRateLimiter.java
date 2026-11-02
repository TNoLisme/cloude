package com.bank.simulator.shared.ratelimit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class InMemoryRateLimiter {

    private final Map<String, Window> windows = new HashMap<>();
    private final int maxEntries;

    public InMemoryRateLimiter(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized Admission admit(String key, RateLimitPolicy policy, long nowNanos) {
        long duration = policy.window().toNanos();
        Window window = windows.get(key);
        if (window == null || nowNanos - window.startedAtNanos >= duration) {
            evictExpired(nowNanos, duration);
            if (windows.size() >= maxEntries) {
                return new Admission(false, 1);
            }
            window = new Window(nowNanos, 0);
            windows.put(key, window);
        }

        if (window.count >= policy.limit()) {
            long remaining = duration - (nowNanos - window.startedAtNanos);
            return new Admission(false, Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
        }
        window.count++;
        return new Admission(true, 0);
    }

    private void evictExpired(long nowNanos, long duration) {
        Iterator<Window> iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next();
            if (nowNanos - window.startedAtNanos >= duration) {
                iterator.remove();
            }
        }
    }

    public record Admission(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Window {
        private final long startedAtNanos;
        private int count;

        private Window(long startedAtNanos, int count) {
            this.startedAtNanos = startedAtNanos;
            this.count = count;
        }
    }
}
