package com.simulatedbank.banking.transfer.domain;

import java.util.Objects;

/** IDs are scalar values; ownership and account status belong to their providers. */
public record TransferIdentity(String reference, long customerId, long initiatedByUserId,
                               long sourceAccountId, long destinationAccountId, Long stepUpChallengeId) {
    public TransferIdentity {
        Objects.requireNonNull(reference, "reference");
        int referenceLength = reference.codePointCount(0, reference.length());
        if (referenceLength < 1 || referenceLength > 32) {
            throw new IllegalArgumentException("Reference must be 1 to 32 characters");
        }
        if (customerId <= 0 || initiatedByUserId <= 0 || sourceAccountId <= 0 || destinationAccountId <= 0
                || (stepUpChallengeId != null && stepUpChallengeId <= 0)) {
            throw new IllegalArgumentException("IDs must be positive");
        }
        if (sourceAccountId == destinationAccountId) {
            throw new IllegalArgumentException("Source and destination accounts must differ");
        }
    }
}
