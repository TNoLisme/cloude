package com.simulatedbank.banking.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransferDomainTest {
    private static final Instant CREATED = Instant.parse("2026-11-16T00:00:00Z");
    private static final Instant TERMINAL = CREATED.plusSeconds(1);

    @Test
    void amountIsExactAndFitsDatabasePrecisionWithoutRounding() {
        assertThat(new TransferAmount(new BigDecimal("0.01"), "VND").value()).isEqualTo(new BigDecimal("0.01"));
        assertThat(new TransferAmount(new BigDecimal("10"), "VND").value()).isEqualTo(new BigDecimal("10.00"));
        assertThat(new TransferAmount(new BigDecimal("99999999999999999.99"), "VND").value())
                .isEqualTo(new BigDecimal("99999999999999999.99"));
        for (String amount : new String[]{"0", "-1", "0.001", "1.000", "100000000000000000", "1E+100"}) {
            assertThatThrownBy(() -> new TransferAmount(new BigDecimal(amount), "VND"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new TransferAmount(BigDecimal.ONE, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferAmount(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityRequiresValidIdsReferenceAndDifferentAccounts() {
        assertThat(new TransferIdentity("r", 1, 2, 3, 4, null).reference()).isEqualTo("r");
        assertThat(new TransferIdentity("😀".repeat(32), 1, 2, 3, 4, 5L).stepUpChallengeId()).isEqualTo(5L);
        assertThatThrownBy(() -> new TransferIdentity("", 1, 2, 3, 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("x".repeat(33), 1, 2, 3, 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 0, 2, 3, 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 1, -1, 3, 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 1, 2, 0, 4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 1, 2, 3, -4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 1, 2, 3, 4, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferIdentity("r", 1, 2, 3, 3, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyPendingCanReachTerminalAndTerminalContainsRequiredData() {
        TransferState pending = TransferState.pending(CREATED);
        TransferState completed = pending.complete(TERMINAL);
        TransferState failed = pending.fail(TERMINAL, "INSUFFICIENT_BALANCE");
        assertThat(pending.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(TERMINAL);
        assertThat(completed.failedAt()).isNull();
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failedAt()).isEqualTo(TERMINAL);
        assertThat(failed.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(failed.completedAt()).isNull();
        for (TransferState terminal : new TransferState[]{completed, failed}) {
            assertThatThrownBy(() -> terminal.complete(TERMINAL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> terminal.fail(TERMINAL, "FAIL")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void terminalDataAndPrimaryClockRegressionAreRejected() {
        TransferState pending = TransferState.pending(CREATED);
        assertThatThrownBy(() -> pending.complete(CREATED.minusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.fail(CREATED.minusNanos(1), "FAIL")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.fail(TERMINAL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.fail(TERMINAL, "F".repeat(33))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferState(TransferStatus.PENDING, CREATED, TERMINAL, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferState(TransferStatus.PENDING, CREATED, null, null, "FAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferState(TransferStatus.COMPLETED, CREATED, TERMINAL, null, "FAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferState(TransferStatus.COMPLETED, CREATED, TERMINAL, TERMINAL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferState(TransferStatus.FAILED, CREATED, null, null, "FAIL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void idempotencyCanReachTerminalWithOrWithoutTransientMarker() {
        assertThat(IdempotencyStatus.RECEIVED.startProcessing()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        for (IdempotencyStatus nonterminal : new IdempotencyStatus[]{IdempotencyStatus.RECEIVED, IdempotencyStatus.IN_PROGRESS}) {
            assertThat(nonterminal.complete()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(nonterminal.fail()).isEqualTo(IdempotencyStatus.FAILED);
        }
        for (IdempotencyStatus terminal : new IdempotencyStatus[]{IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED}) {
            assertThatThrownBy(terminal::startProcessing).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(terminal::complete).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(terminal::fail).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(IdempotencyStatus.IN_PROGRESS::startProcessing).isInstanceOf(IllegalStateException.class);
    }

}
