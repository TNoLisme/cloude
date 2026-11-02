package com.bank.simulator.risk.application;

import java.time.Instant;
import java.util.UUID;

public record TransferRiskSnapshot(UUID transferId, UUID sourceAccountId, String amount,
                                   Instant completedAt, UUID correlationId) {}
