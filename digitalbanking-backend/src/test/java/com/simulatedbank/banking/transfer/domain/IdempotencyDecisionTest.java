package com.simulatedbank.banking.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyDecisionTest {
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Test
    void absentRecordIsNewOnlyAfterSuccessfulLookup() {
        assertThat(IdempotencyDecision.classify(1, 2, HASH, Optional.empty()))
                .isEqualTo(IdempotencyDecision.NEW);
        assertThatThrownBy(() -> IdempotencyDecision.classify(1, 2, HASH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sameActorCustomerAndPayloadReplayTerminalAndWaitForNonterminal() {
        for (IdempotencyStatus status : IdempotencyStatus.values()) {
            IdempotencyDecision expected = switch (status) {
                case RECEIVED, IN_PROGRESS -> IdempotencyDecision.IN_PROGRESS;
                case COMPLETED, FAILED -> IdempotencyDecision.REPLAY;
            };
            assertThat(IdempotencyDecision.classify(1, 2, HASH, existing(1, 2, HASH, status)))
                    .as("status %s", status).isEqualTo(expected);
        }
    }

    @Test
    void anyIdentityOrPayloadMismatchConflictsRegardlessOfStoredState() {
        for (IdempotencyStatus status : IdempotencyStatus.values()) {
            assertThat(IdempotencyDecision.classify(1, 2, HASH, existing(3, 2, HASH, status)))
                    .isEqualTo(IdempotencyDecision.CONFLICT);
            assertThat(IdempotencyDecision.classify(1, 2, HASH, existing(1, 3, HASH, status)))
                    .isEqualTo(IdempotencyDecision.CONFLICT);
            assertThat(IdempotencyDecision.classify(1, 2, HASH, existing(1, 2, OTHER_HASH, status)))
                    .isEqualTo(IdempotencyDecision.CONFLICT);
        }
    }

    @Test
    void invalidIdentityOrCorruptStoredRecordFailsClosedAndDoesNotExposeHash() {
        assertThatThrownBy(() -> IdempotencyDecision.classify(0, 2, HASH, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyDecision.classify(1, 0, HASH, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String badHash : new String[]{"", "A".repeat(64), "z".repeat(64), "a".repeat(63)}) {
            assertThatThrownBy(() -> IdempotencyDecision.classify(1, 2, badHash, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IdempotencyDecision.Existing(1, 2, badHash, IdempotencyStatus.RECEIVED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new IdempotencyDecision.Existing(1, 2, HASH, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(new IdempotencyDecision.Existing(1, 2, HASH, IdempotencyStatus.COMPLETED).toString())
                .doesNotContain(HASH);
    }

    private static Optional<IdempotencyDecision.Existing> existing(long actor, long customer, String hash,
                                                                    IdempotencyStatus status) {
        return Optional.of(new IdempotencyDecision.Existing(actor, customer, hash, status));
    }
}
