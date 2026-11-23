package com.simulatedbank.banking.customer.api;

import java.time.Instant;

/** Internal SMS routing for trusted server jobs. Each method fixes the allowed purpose and ID scope. */
public interface CustomerDeliveryContact {
    Result forOtpUser(long userId);

    Result forTransferOutcome(long customerId);

    sealed interface Result permits Available, Unavailable {
    }

    record Available(String phone, Instant version) implements Result {
        @Override
        public String toString() {
            return "Available[redacted]";
        }
    }

    enum Unavailable implements Result {
        NOT_FOUND, INACTIVE, NO_CONTACT, NO_CONSENT
    }
}
