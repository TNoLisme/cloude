package com.bank.simulator.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitInterceptorTest {

    @Test
    void throwsTypedRateLimitExceptionAfterQuota() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(10);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, new IncrementingClock());
        RateLimitPolicy policy = new RateLimitPolicy("login", 1, Duration.ofSeconds(60));

        interceptor.check("login:ip:test", policy);

        assertThatThrownBy(() -> interceptor.check("login:ip:test", policy))
                .isInstanceOf(RateLimitInterceptor.RateLimitedException.class)
                .extracting(exception -> ((RateLimitInterceptor.RateLimitedException) exception).retryAfterSeconds())
                .isEqualTo(60L);
    }

    private static final class IncrementingClock implements java.util.function.LongSupplier {
        private long now;
        @Override
        public long getAsLong() {
            return now++;
        }
    }
}
