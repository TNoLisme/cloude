package com.simulatedbank.banking.transfer.application;

import java.util.Objects;

/**
 * Typed create outcomes the HTTP adapter maps to API contracts §8.3.1. The orchestrator
 * (BE-TRF-04/05) produces these; the boundary never invents a terminal state. Pre-T1
 * denials carry no reference; post-T1 {@link Failed} carries a committed FAILED receipt.
 */
public sealed interface TransferCreateOutcome {

    record Completed(TransferView view) implements TransferCreateOutcome {
        public Completed { Objects.requireNonNull(view, "view"); }
    }

    record Failed(TransferView view) implements TransferCreateOutcome {
        public Failed {
            Objects.requireNonNull(view, "view");
            if (view.status() != com.simulatedbank.banking.transfer.domain.TransferStatus.FAILED) {
                throw new IllegalArgumentException("Failed outcome requires a FAILED receipt");
            }
        }
    }

    record StepUpRequired(StepUpChallenge challenge) implements TransferCreateOutcome {
        public StepUpRequired { Objects.requireNonNull(challenge, "challenge"); }
    }

    record InProgress() implements TransferCreateOutcome { }

    /** Stored terminal response replayed verbatim; body keeps the original correlationId. */
    record Replay(int httpStatus, String body) implements TransferCreateOutcome {
        public Replay {
            if ((httpStatus != 201 && httpStatus != 422) || body == null) {
                throw new IllegalArgumentException("Invalid stored replay response");
            }
        }
        @Override public String toString() { return "Replay[status=" + httpStatus + ", redacted]"; }
    }

    record Conflict() implements TransferCreateOutcome { }

    record AccountNotFound() implements TransferCreateOutcome { }

    record AccountInactive() implements TransferCreateOutcome { }

    record SourceNotOwned() implements TransferCreateOutcome { }

    record InsufficientBalance() implements TransferCreateOutcome { }

    record LimitExceeded() implements TransferCreateOutcome { }
}