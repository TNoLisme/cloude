package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyRetentionTest {
    private static final Instant CREATED = Instant.parse("2026-11-16T00:00:00Z");
    private static final IdempotencyRetention.Snapshot DAY = new IdempotencyRetention.Snapshot(86400, "v1");
    private static final IdempotencyRetention.Snapshot TWO_DAYS = new IdempotencyRetention.Snapshot(172800, "v2");

    @Test
    void terminalSnapshotCanExtendButNeverShortensInitialExpiry() {
        Instant initial = IdempotencyRetention.initialExpiry(CREATED, TWO_DAYS);
        assertThat(IdempotencyRetention.terminalExpiry(initial, CREATED.plusSeconds(1), DAY)).isEqualTo(initial);
        assertThat(IdempotencyRetention.terminalExpiry(initial, CREATED.plusSeconds(1), TWO_DAYS))
                .isEqualTo(initial.plusSeconds(1));
        Instant lateTerminal = CREATED.plusSeconds(10 * 86400L);
        assertThat(IdempotencyRetention.terminalExpiry(initial, lateTerminal, DAY))
                .isEqualTo(lateTerminal.plusSeconds(86400));
    }

    @Test
    void nonterminalNeverEligibleEvenAfterExpiry() {
        Instant expiry = IdempotencyRetention.initialExpiry(CREATED, DAY);
        for (IdempotencyStatus status : new IdempotencyStatus[]{IdempotencyStatus.RECEIVED, IdempotencyStatus.IN_PROGRESS}) {
            assertThat(IdempotencyRetention.eligibleForPurge(status, expiry, expiry.plusSeconds(1))).isFalse();
        }
        for (IdempotencyStatus status : new IdempotencyStatus[]{IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED}) {
            assertThat(IdempotencyRetention.eligibleForPurge(status, expiry, expiry.minusNanos(1))).isFalse();
            assertThat(IdempotencyRetention.eligibleForPurge(status, expiry, expiry)).isFalse();
            assertThat(IdempotencyRetention.eligibleForPurge(status, expiry, expiry.plusNanos(1))).isTrue();
        }
    }

    @Test
    void badConfigurationOrTimeFailsClosed() {
        assertThatThrownBy(() -> new IdempotencyRetention.Snapshot(86399, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyRetention.Snapshot(86400, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyRetention.Snapshot(86400, "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyRetention.initialExpiry(CREATED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyRetention.terminalExpiry(null, CREATED, DAY))
                .isInstanceOf(NullPointerException.class);
    }
}
