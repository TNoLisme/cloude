package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.crypto.HmacService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, process-local token buckets (virtual scheduling, no floating-point counters). Identity can use this for normalized username quotas. */
@Component
public final class RequestRateLimiter {
    private final Clock clock;
    private final int capacity;
    private final Map<String, Window> windows = new HashMap<>();

    public RequestRateLimiter(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.capacity = properties.security().http().maxRateLimitBuckets();
    }

    /** Returns zero when allowed; otherwise ceil(seconds until the next token). Subjects are never stored raw. */
    public synchronized long acquire(String group, String subject, int limit, Duration duration) {
        if (limit <= 0 || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Invalid rate limit configuration");
        }
        Instant now = clock.instant();
        String key = group + ":" + HmacService.sha256Hex(subject);
        Window window = windows.get(key);
        if (window == null) {
            if (window == null && windows.size() >= capacity) {
                windows.values().removeIf(value -> !now.isBefore(value.next));
                if (windows.size() >= capacity) {
                    throw new CapacityExceededException();
                }
            }
            window = new Window(now);
            windows.put(key, window);
        }
        // Each request reserves one refill interval. Tolerance permits a burst of exactly limit tokens.
        Duration spacing = Duration.ofNanos(Math.ceilDiv(duration.toNanos(), limit));
        Instant eligible = window.next.minus(spacing.multipliedBy(limit - 1L));
        if (now.isBefore(eligible)) {
            Duration remaining = Duration.between(now, eligible);
            return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        }
        window.next = (now.isAfter(window.next) ? now : window.next).plus(spacing);
        return 0;
    }

    static final class CapacityExceededException extends RuntimeException {
        CapacityExceededException() { super("Rate limiter capacity exceeded"); }
    }

    private static final class Window {
        private Instant next;
        private Window(Instant next) { this.next = next; }
    }
}
