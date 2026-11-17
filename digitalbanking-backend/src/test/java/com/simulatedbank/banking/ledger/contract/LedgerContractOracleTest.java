package com.simulatedbank.banking.ledger.contract;

import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerContractOracleTest {

    private static final long TRANSFER_ID = 15;
    private static final long SOURCE_ID = 20;
    private static final long DESTINATION_ID = 23;
    private static final String CORRELATION = "transfer:15";
    private static final BigDecimal AMOUNT = new BigDecimal("125.50");
    private static final BigDecimal SOURCE_AFTER = BigDecimal.ZERO;
    private static final BigDecimal DESTINATION_AFTER = new BigDecimal("325.50");
    private static final Posting POSTING = new Posting(TRANSFER_ID, SOURCE_ID, DESTINATION_ID, AMOUNT, "VND",
            SOURCE_AFTER, DESTINATION_AFTER, CORRELATION);
    private static final Entry DEBIT = new Entry(31, 1, Side.DEBIT, SOURCE_ID, AMOUNT, "VND", SOURCE_AFTER, CORRELATION);
    private static final Entry CREDIT = new Entry(34, 2, Side.CREDIT, DESTINATION_ID, AMOUNT, "VND",
            DESTINATION_AFTER, CORRELATION);
    private static final Instant OPENED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant POSTED_AT = OPENED_AT.plusSeconds(1);

    @Test
    void acceptsSchemaMaximumAmountAndZeroBalanceFromAccount() {
        AccountMoney account = mock(AccountMoney.class);
        when(account.ledgerBalanceAfter(SOURCE_ID)).thenReturn(BigDecimal.ZERO);
        when(account.ledgerBalanceAfter(DESTINATION_ID)).thenReturn(DESTINATION_AFTER);

        Posting command = new Posting(TRANSFER_ID, SOURCE_ID, DESTINATION_ID, new BigDecimal("99999999999999999.99"),
                "VND", account.ledgerBalanceAfter(SOURCE_ID), account.ledgerBalanceAfter(DESTINATION_ID), CORRELATION);

        assertThat(valid(command)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "0.001", "1.010", "100000000000000000.00", "1E+17"})
    @NullSource
    void rejectsInvalidAmount(String amount) {
        assertThat(valid(new Posting(TRANSFER_ID, SOURCE_ID, DESTINATION_ID,
                amount == null ? null : new BigDecimal(amount), "VND", SOURCE_AFTER, DESTINATION_AFTER, CORRELATION)))
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPostings")
    void rejectsInvalidPostingMetadata(String reason, Posting posting) {
        assertThat(valid(posting)).as(reason).isFalse();
    }

    static Stream<Arguments> invalidPostings() {
        return Stream.of(
                arguments("same account", new Posting(15, 20, 20, AMOUNT, "VND", SOURCE_AFTER,
                        DESTINATION_AFTER, CORRELATION)),
                arguments("zero transfer ID", new Posting(0, 20, 23, AMOUNT, "VND", SOURCE_AFTER,
                        DESTINATION_AFTER, CORRELATION)),
                arguments("nonpositive account ID", new Posting(15, -1, 23, AMOUNT, "VND", SOURCE_AFTER,
                        DESTINATION_AFTER, CORRELATION)),
                arguments("unsupported currency", new Posting(15, 20, 23, AMOUNT, "USD", SOURCE_AFTER,
                        DESTINATION_AFTER, CORRELATION)),
                arguments("negative balance", new Posting(15, 20, 23, AMOUNT, "VND", new BigDecimal("-0.01"),
                        DESTINATION_AFTER, CORRELATION)),
                arguments("missing balance", new Posting(15, 20, 23, AMOUNT, "VND", SOURCE_AFTER, null, CORRELATION)),
                arguments("invalid correlation", new Posting(15, 20, 23, AMOUNT, "VND", SOURCE_AFTER,
                        DESTINATION_AFTER, "bad correlation")));
    }

    @Test
    void matchesBothAccountSnapshotsAndCopiesRawRows() {
        List<Entry> rows = new ArrayList<>(List.of(DEBIT, CREDIT));
        Evidence evidence = new Evidence(TRANSFER_ID, rows);
        rows.clear();

        assertThat(assess(POSTING, Optional.of(evidence))).isEqualTo(Pair.MATCH);
        assertThatThrownBy(() -> evidence.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badEvidence")
    void treatsEveryInvalidPairAsAnIncident(String reason, long transferId, List<Entry> rows) {
        assertThat(assess(POSTING, Optional.of(new Evidence(transferId, rows)))).as(reason).isEqualTo(Pair.INCIDENT);
    }

    static Stream<Arguments> badEvidence() {
        return Stream.of(
                arguments("wrong transfer", 99L, List.of(DEBIT, CREDIT)),
                arguments("present without rows", TRANSFER_ID, List.of()),
                arguments("partial", TRANSFER_ID, List.of(DEBIT)),
                arguments("extra", TRANSFER_ID, List.of(DEBIT, CREDIT, CREDIT)),
                arguments("wrong sequence order", TRANSFER_ID, List.of(CREDIT, DEBIT)),
                arguments("duplicate ID", TRANSFER_ID, List.of(DEBIT, new Entry(DEBIT.id(), 2, Side.CREDIT,
                        DESTINATION_ID, AMOUNT, "VND", DESTINATION_AFTER, CORRELATION))),
                arguments("wrong side", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.DEBIT,
                        DESTINATION_ID, AMOUNT, "VND", DESTINATION_AFTER, CORRELATION))),
                arguments("wrong account", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.CREDIT,
                        SOURCE_ID, AMOUNT, "VND", DESTINATION_AFTER, CORRELATION))),
                arguments("wrong amount", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.CREDIT,
                        DESTINATION_ID, new BigDecimal("125.51"), "VND", DESTINATION_AFTER, CORRELATION))),
                arguments("wrong currency", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.CREDIT,
                        DESTINATION_ID, AMOUNT, "USD", DESTINATION_AFTER, CORRELATION))),
                arguments("wrong snapshot", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.CREDIT,
                        DESTINATION_ID, AMOUNT, "VND", BigDecimal.ZERO, CORRELATION))),
                arguments("wrong correlation", TRANSFER_ID, List.of(DEBIT, new Entry(34, 2, Side.CREDIT,
                        DESTINATION_ID, AMOUNT, "VND", DESTINATION_AFTER, "other-correlation"))));
    }

    @Test
    void distinguishesSuccessfulAbsenceFromAPrimaryReadError() {
        RecoveryLedger primary = mock(RecoveryLedger.class);
        when(primary.findEvidence(TRANSFER_ID)).thenReturn(Optional.empty());
        assertThat(assess(POSTING, primary.findEvidence(TRANSFER_ID))).isEqualTo(Pair.ABSENT);

        IllegalStateException unavailable = new IllegalStateException("primary unavailable");
        when(primary.findEvidence(TRANSFER_ID)).thenThrow(unavailable);
        assertThatThrownBy(() -> assess(POSTING, primary.findEvidence(TRANSFER_ID))).isSameAs(unavailable);
    }

    @Test
    void includesEvidencedSeedAndChecksBothDebitAndCredit() {
        AccountOpening account = mock(AccountOpening.class);
        when(account.readOpening(SOURCE_ID))
                .thenReturn(Optional.of(new Opening(SOURCE_ID, new BigDecimal("200.00"), "VND", OPENED_AT, "seed-1")));
        List<Movement> rows = List.of(new Movement(Side.DEBIT, AMOUNT, POSTED_AT),
                new Movement(Side.CREDIT, new BigDecimal("25.50"), POSTED_AT));

        assertThat(assessBalance(SOURCE_ID, new BigDecimal("100.00"), account.readOpening(SOURCE_ID), rows))
                .isEqualTo(Balance.MATCH);
        assertThat(assessBalance(SOURCE_ID, BigDecimal.ZERO, account.readOpening(SOURCE_ID), rows))
                .isEqualTo(Balance.MISMATCH);
    }

    @Test
    void requiresEvidenceForZeroOpeningAndAcceptsAnEmptyPostingSet() {
        Opening opening = new Opening(SOURCE_ID, BigDecimal.ZERO, "VND", OPENED_AT, "opening-0");
        assertThat(assessBalance(SOURCE_ID, BigDecimal.ZERO, Optional.of(opening), List.of())).isEqualTo(Balance.MATCH);
        assertThat(assessBalance(SOURCE_ID, BigDecimal.ZERO, Optional.empty(), List.of())).isEqualTo(Balance.UNPROVEN);
    }

    @Test
    void rejectsOpeningAtOrAfterTheFirstPosting() {
        Opening late = new Opening(SOURCE_ID, AMOUNT, "VND", POSTED_AT, "seed-2");
        assertThat(assessBalance(SOURCE_ID, BigDecimal.ZERO, Optional.of(late),
                List.of(new Movement(Side.DEBIT, AMOUNT, POSTED_AT)))).isEqualTo(Balance.UNPROVEN);
    }

    private interface AccountMoney {
        BigDecimal ledgerBalanceAfter(long accountId);
    }

    private interface AccountOpening {
        Optional<Opening> readOpening(long accountId);
    }

    private interface RecoveryLedger {
        Optional<Evidence> findEvidence(long transferId);
    }
}
