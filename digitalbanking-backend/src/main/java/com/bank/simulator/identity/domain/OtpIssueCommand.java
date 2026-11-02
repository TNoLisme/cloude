package com.bank.simulator.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record OtpIssueCommand(UUID challengeId, String identifier, OtpChannel channel,
                              OtpPurpose purpose, Instant expiresAt, String code) {
}
