package com.bank.simulator.identity.infrastructure.otp;

import java.time.Instant;
import java.util.UUID;

public record OtpMessage(UUID challengeId, String identifier, String code, Instant expiresAt) {
}
