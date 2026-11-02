package com.bank.simulator.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    @Test
    void allowsLimitThenRejectsWithRetryAfter() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(10);
        RateLimitPolicy policy = new RateLimitPolicy("test", 2, Duration.ofSeconds(60));

        assertThat(limiter.admit("key", policy, 0).allowed()).isTrue();
        assertThat(limiter.admit("key", policy, 1).allowed()).isTrue();
        var rejected = limiter.admit("key", policy, 2);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void resetsAfterWindowAndSeparatesKeys() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(10);
        RateLimitPolicy policy = new RateLimitPolicy("test", 1, Duration.ofSeconds(60));

        assertThat(limiter.admit("one", policy, 0).allowed()).isTrue();
        assertThat(limiter.admit("two", policy, 0).allowed()).isTrue();
        assertThat(limiter.admit("one", policy, Duration.ofSeconds(60).toNanos()).allowed()).isTrue();
    }
}
