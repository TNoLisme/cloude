package com.bank.simulator.transfer.application;

import java.time.Instant;
import java.util.UUID;

public record TransferCommittedEvent(UUID transferId, UUID sourceAccountId, String amount,
                                     Instant completedAt, UUID correlationId) {}
