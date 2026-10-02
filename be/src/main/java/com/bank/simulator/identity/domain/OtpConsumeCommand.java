package com.bank.simulator.identity.domain;

import java.util.UUID;

public record OtpConsumeCommand(UUID challengeId, String identifier, OtpChannel channel,
                                OtpPurpose purpose, String code) {
}
