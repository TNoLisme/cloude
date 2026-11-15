package com.simulatedbank.banking.identity.api;

import java.time.Instant;
import java.util.UUID;

public interface OtpDeliveryPort {
    void deliver(OtpDelivery delivery);

    record OtpDelivery(UUID challengeId, long userId, String purpose, String channel,
            String oneTimeCode, Instant expiresAt, String correlationId) {
        @Override
        public String toString() {
            return "OtpDelivery[redacted]";
        }
    }
}
