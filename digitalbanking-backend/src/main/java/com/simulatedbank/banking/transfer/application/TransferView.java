package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.time.Instant;
import java.util.Objects;

/** Masked transfer receipt projection used for both the create response and the GET receipt. */
public record TransferView(long transferId, String reference, TransferStatus status, TransferAmount amount,
                           String description, MaskedAccount source, MaskedAccount destination,
                           String correlationId, Instant createdAt, Instant completedAt, Instant failedAt,
                           TransferFailureCode failureCode, String failureMessage) {
    public TransferView {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (transferId <= 0) throw new IllegalArgumentException("Invalid transfer ID");
        if (reference.isBlank() || reference.codePointCount(0, reference.length()) > 32) {
            throw new IllegalArgumentException("Invalid reference");
        }
        if (status == TransferStatus.FAILED && failureCode == null) {
            throw new IllegalArgumentException("FAILED view requires a failure code");
        }
        if (status != TransferStatus.FAILED && (failureCode != null || failureMessage != null)) {
            throw new IllegalArgumentException("Failure details require FAILED status");
        }
        description = description == null ? null : description.trim();
    }

    @Override
    public String toString() {
        return "TransferView[reference=" + reference + ", status=" + status + "]";
    }
}