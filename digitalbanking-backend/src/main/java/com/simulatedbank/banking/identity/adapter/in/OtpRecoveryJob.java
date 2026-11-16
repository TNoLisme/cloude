package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.identity.application.OtpExpiryRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Operational expiry sweep only; does not repurpose the recovery-code TTL as a retry policy. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class OtpRecoveryJob {
    private final OtpExpiryRecoveryService recovery;

    public OtpRecoveryJob(OtpExpiryRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${app.identity.otp-expiry-sweep-delay:PT1M}",
            initialDelayString = "${app.identity.otp-expiry-sweep-delay:PT1M}")
    public void expireAbandonedChallenges() {
        recovery.recoverExpired();
    }
}
