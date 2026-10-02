package com.bank.simulator.shared.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RateLimitPolicy {

    private final String name;
    private final int limit;
    private final Duration window;

    public RateLimitPolicy(String name, int limit, Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit policy must have positive limit and window");
        }
        this.name = name;
        this.limit = limit;
        this.window = window;
    }

    public String name() { return name; }
    public int limit() { return limit; }
    public Duration window() { return window; }

    public static List<RateLimitPolicy> defaults() {
        return List.of(
                new RateLimitPolicy("login", 5, Duration.ofSeconds(60)),
                new RateLimitPolicy("registration-otp", 3, Duration.ofSeconds(300)),
                new RateLimitPolicy("recovery-initiate", 3, Duration.ofSeconds(300)),
                new RateLimitPolicy("operator-otp", 10, Duration.ofSeconds(300)),
                new RateLimitPolicy("recipient-resolve", 30, Duration.ofSeconds(60)),
                new RateLimitPolicy("operator-lookup", 30, Duration.ofSeconds(60))
        );
    }
}
