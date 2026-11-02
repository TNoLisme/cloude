package com.bank.simulator.transfer.domain;

import java.time.Instant;

public final class TransferExpirationPolicy {
    private TransferExpirationPolicy() {}

    public static TransferStatus resolveEffectiveStatus(TransferStatus status, Instant expiresAt, Instant now) {
        if (status == TransferStatus.AWAITING_OTP && expiresAt != null && now.isAfter(expiresAt)) {
            return TransferStatus.EXPIRED;
        }
        return status;
    }
}
