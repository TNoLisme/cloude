package com.bank.simulator.risk.application;

import java.time.Instant;
import java.util.UUID;

public record RiskFlagView(UUID flagId, UUID transferId, String ruleId, String ruleVersion,
                           String reason, Instant detectedAt) {}
