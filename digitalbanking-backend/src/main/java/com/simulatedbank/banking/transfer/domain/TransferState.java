package com.simulatedbank.banking.transfer.domain;

import java.time.Instant;
import java.util.Objects;

/** State alone cannot guarantee atomicity: the persistence layer must use a conditional update. */
public record TransferState(TransferStatus status, Instant createdAt, Instant completedAt,
                            Instant failedAt, String failureCode) {
    public TransferState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (failureCode != null && failureCode.codePointCount(0, failureCode.length()) > 32) {
            throw new IllegalArgumentException("Failure code exceeds 32 characters");
        }
        if (status != TransferStatus.FAILED && failureCode != null) {
            throw new IllegalArgumentException("Only FAILED transfers can have a failure code");
        }
        switch (status) {
            case PENDING -> {
                if (completedAt != null || failedAt != null) {
                    throw new IllegalArgumentException("PENDING cannot have terminal data");
                }
            }
            case COMPLETED -> {
                if (completedAt == null || failedAt != null || completedAt.isBefore(createdAt)) {
                    throw new IllegalArgumentException("Invalid COMPLETED timestamp or failure code");
                }
            }
            case FAILED -> {
                if (failedAt == null || completedAt != null || failureCode == null || failedAt.isBefore(createdAt)) {
                    throw new IllegalArgumentException("Invalid FAILED timestamp or failure code");
                }
            }
        }
    }

    public static TransferState pending(Instant createdAt) {
        return new TransferState(TransferStatus.PENDING, createdAt, null, null, null);
    }

    public TransferState complete(Instant terminalAt) {
        requirePending();
        return new TransferState(TransferStatus.COMPLETED, createdAt, terminalAt, null, null);
    }

    public TransferState fail(Instant terminalAt, String failureCode) {
        requirePending();
        return new TransferState(TransferStatus.FAILED, createdAt, null, terminalAt, failureCode);
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Terminal transfer cannot change status");
        }
    }
}
