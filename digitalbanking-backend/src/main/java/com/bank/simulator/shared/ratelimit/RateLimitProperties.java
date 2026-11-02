package com.bank.simulator.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        int loginLimit,
        Duration loginWindow,
        int registrationOtpLimit,
        Duration registrationOtpWindow,
        int recoveryInitiateLimit,
        Duration recoveryInitiateWindow,
        int recoveryVerifyLimit,
        Duration recoveryVerifyWindow,
        int operatorOtpLimit,
        Duration operatorOtpWindow,
        int recipientResolveLimit,
        Duration recipientResolveWindow,
        int operatorLookupLimit,
        Duration operatorLookupWindow,
        int maxEntries
) {
}
