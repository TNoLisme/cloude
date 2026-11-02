package com.bank.simulator.shared.ratelimit;

import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

@Component
public class RateLimitInterceptor {

    private final InMemoryRateLimiter limiter;
    private final LongSupplier nanoClock;

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitInterceptor(RateLimitProperties properties) {
        this(new InMemoryRateLimiter(properties.maxEntries()), System::nanoTime);
    }

    RateLimitInterceptor(InMemoryRateLimiter limiter, LongSupplier nanoClock) {
        this.limiter = Objects.requireNonNull(limiter);
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public void check(String key, RateLimitPolicy policy) {
        InMemoryRateLimiter.Admission admission = limiter.admit(key, policy, nanoClock.getAsLong());
        if (!admission.allowed()) {
            throw new RateLimitedException(admission.retryAfterSeconds());
        }
    }

    public static class RateLimitedException extends ApiException {
        private final long retryAfterSeconds;

        public RateLimitedException(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
