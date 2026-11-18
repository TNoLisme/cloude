package com.simulatedbank.banking.ledger.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LED-POST-10/11: every read-back mutation is rejected; count or signed sum alone is not enough. */
class PairVerifierTest {

    private static final long TRANSFER = 15;
    private static final long SOURCE = 20;
    private static final long DESTINATION = 23;
    private static final long DEBIT_ID = 31;
    private static final long CREDIT_ID = 34;
    private static final BigDecimal AMOUNT = new BigDecimal("125.50");
    private static final BigDecimal SOURCE_AFTER = BigDecimal.ZERO;
    private static final BigDecimal DEST_AFTER = new BigDecimal("325.50");
    private static final String CORRELATION = "transfer:15";

    private static final ValidatedPosting POSTING = ValidatedPosting.of(TRANSFER, SOURCE, DESTINATION, AMOUNT,
            "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION);
    private static final PostedEntry DEBIT = new PostedEntry(DEBIT_ID, TRANSFER, SOURCE, 1, EntrySide.DEBIT,
            AMOUNT, "VND", SOURCE_AFTER, CORRELATION);
    private static final PostedEntry CREDIT = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT,
            AMOUNT, "VND", DEST_AFTER, CORRELATION);

    private static void verify(List<PostedEntry> entries) {
        PairVerifier.verify(POSTING, entries, DEBIT_ID, CREDIT_ID);
    }

    @Test
    void acceptsAFullyMatchingPair() {
        assertThatCode(() -> verify(List.of(DEBIT, CREDIT))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingPartialAndExtraEntries() {
        assertThatThrownBy(() -> verify(List.of())).isInstanceOf(LedgerInvariantException.class);
        assertThatThrownBy(() -> verify(List.of(DEBIT))).isInstanceOf(LedgerInvariantException.class);
        assertThatThrownBy(() -> verify(List.of(DEBIT, CREDIT, CREDIT))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongTransferId() {
        PostedEntry wrong = new PostedEntry(CREDIT_ID, 99, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "VND",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, wrong))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsDuplicateOrNonPositiveEntryIds() {
        PostedEntry duplicate = new PostedEntry(DEBIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "VND",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, duplicate))).isInstanceOf(LedgerInvariantException.class);
        PostedEntry zero = new PostedEntry(0, TRANSFER, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "VND",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, zero))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsGeneratedIdMismatchAtEitherSide() {
        assertThatThrownBy(() -> PairVerifier.verify(POSTING, List.of(DEBIT, CREDIT), CREDIT_ID, CREDIT_ID))
                .isInstanceOf(LedgerInvariantException.class);
        assertThatThrownBy(() -> PairVerifier.verify(POSTING, List.of(DEBIT, CREDIT), DEBIT_ID, DEBIT_ID))
                .isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongSequenceOrder() {
        assertThatThrownBy(() -> verify(List.of(CREDIT, DEBIT))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongSide() {
        PostedEntry wrong = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.DEBIT, AMOUNT, "VND",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, wrong))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongAccount() {
        PostedEntry wrong = new PostedEntry(CREDIT_ID, TRANSFER, SOURCE, 2, EntrySide.CREDIT, AMOUNT, "VND",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, wrong))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsAmountMismatchEvenWhenSignedSumIsZero() {
        // Both lines carry 200.00 (sum zero, count two) but the command amount is 125.50.
        PostedEntry debit = new PostedEntry(DEBIT_ID, TRANSFER, SOURCE, 1, EntrySide.DEBIT,
                new BigDecimal("200.00"), "VND", SOURCE_AFTER, CORRELATION);
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT,
                new BigDecimal("200.00"), "VND", DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(debit, credit))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsUnequalAmounts() {
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT,
                new BigDecimal("125.51"), "VND", DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, credit))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsInvalidAmountRepresentationOnReadBack() {
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT,
                new BigDecimal("125.500"), "VND", DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, credit))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongCurrency() {
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "USD",
                DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, credit))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongBalanceSnapshot() {
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "VND",
                BigDecimal.ZERO, CORRELATION);
        assertThatThrownBy(() -> verify(List.of(DEBIT, credit))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsNegativeBalanceOnReadBack() {
        PostedEntry debit = new PostedEntry(DEBIT_ID, TRANSFER, SOURCE, 1, EntrySide.DEBIT, AMOUNT, "VND",
                new BigDecimal("-0.01"), CORRELATION);
        assertThatThrownBy(() -> verify(List.of(debit, CREDIT))).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsWrongCorrelationEvenWhenCountAndSumAreValid() {
        PostedEntry credit = new PostedEntry(CREDIT_ID, TRANSFER, DESTINATION, 2, EntrySide.CREDIT, AMOUNT, "VND",
                DEST_AFTER, "other-correlation");
        assertThatThrownBy(() -> verify(List.of(DEBIT, credit))).isInstanceOf(LedgerInvariantException.class);
    }
}