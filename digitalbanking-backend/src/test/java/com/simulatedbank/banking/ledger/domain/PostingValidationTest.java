package com.simulatedbank.banking.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** LED-POST-02..05: command validation boundaries before any INSERT (BE-LED-03 §5.2). */
class PostingValidationTest {

    private static final long TRANSFER = 15;
    private static final long SOURCE = 20;
    private static final long DESTINATION = 23;
    private static final BigDecimal AMOUNT = new BigDecimal("125.50");
    private static final BigDecimal SOURCE_AFTER = BigDecimal.ZERO;
    private static final BigDecimal DEST_AFTER = new BigDecimal("325.50");
    private static final String CORRELATION = "transfer:15";

    private static ValidatedPosting of(long transfer, long source, long destination, BigDecimal amount,
            String currency, BigDecimal sourceAfter, BigDecimal destAfter, String correlation) {
        return ValidatedPosting.of(transfer, source, destination, amount, currency, sourceAfter, destAfter, correlation);
    }

    private static ValidatedPosting valid() {
        return of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION);
    }

    @Test
    void acceptsValidCommandAndNormalizesMoneyToScaleTwo() {
        ValidatedPosting posting = valid();
        assertThat(posting.transferId()).isEqualTo(TRANSFER);
        assertThat(posting.amount().value()).isEqualByComparingTo("125.50");
        assertThat(posting.amount().value().scale()).isEqualTo(2);
        assertThat(posting.sourceBalanceAfter().value()).isEqualByComparingTo("0");
        assertThat(posting.destinationBalanceAfter().value()).isEqualByComparingTo("325.50");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveTransferId(long transfer) {
        assertThatThrownBy(() -> of(transfer, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveAccountIds(long id) {
        assertThatThrownBy(() -> of(TRANSFER, id, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, id, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @Test
    void rejectsSameSourceAndDestination() {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, SOURCE, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    @NullSource
    void rejectsNonPositiveAmount(String amount) {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, amount == null ? null : new BigDecimal(amount),
                "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1.0", "1.00", "99999999999999999.99"})
    void acceptsAmountBoundaries(String amount) {
        assertThat(of(TRANSFER, SOURCE, DESTINATION, new BigDecimal(amount), "VND", SOURCE_AFTER, DEST_AFTER, CORRELATION)
                .amount().value()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.000", "1.235", "0.001", "100000000000000000.00", "1E+17"})
    void rejectsAmountScaleOrPrecisionOverflowBeforeNormalization(String amount) {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, new BigDecimal(amount), "VND",
                SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1"})
    @NullSource
    void rejectsNegativeOrNullBalance(String balance) {
        BigDecimal value = balance == null ? null : new BigDecimal(balance);
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", value, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, value, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.01", "99999999999999999.99"})
    void acceptsZeroAndMaxBalance(String balance) {
        assertThat(of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", new BigDecimal(balance), DEST_AFTER, CORRELATION)
                .sourceBalanceAfter().value()).isEqualByComparingTo(balance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.000", "2.345", "100000000000000000.00"})
    void rejectsBalanceScaleOrPrecisionOverflow(String balance) {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER,
                new BigDecimal(balance), CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @Test
    void comparesEqualMoneyByValueNotScale() {
        ValidatedPosting one = of(TRANSFER, SOURCE, DESTINATION, new BigDecimal("1.0"), "VND",
                SOURCE_AFTER, DEST_AFTER, CORRELATION);
        ValidatedPosting two = of(TRANSFER, SOURCE, DESTINATION, new BigDecimal("1.00"), "VND",
                SOURCE_AFTER, DEST_AFTER, CORRELATION);
        assertThat(one.amount().value().compareTo(two.amount().value())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vnd", "VND ", "USD", "", "VnD"})
    @NullSource
    void rejectsCurrencyOtherThanExactVnd(String currency) {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, AMOUNT, currency, SOURCE_AFTER, DEST_AFTER, CORRELATION))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad correlation", "a b",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bad#char"})
    @NullSource
    void rejectsInvalidCorrelation(String correlation) {
        assertThatThrownBy(() -> of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, correlation))
                .isInstanceOf(InvalidPostingCommandException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "transfer:15", "A_Z.0-9:x",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void acceptsCorrelationBoundaries(String correlation) {
        assertThat(of(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND", SOURCE_AFTER, DEST_AFTER, correlation)
                .correlationId()).isEqualTo(correlation);
    }

    @Test
    void buildsDebitAndCreditDraftsWithCorrectSidesAndSeq() {
        ValidatedPosting posting = valid();
        EntryDraft debit = posting.debitDraft();
        EntryDraft credit = posting.creditDraft();
        assertThat(debit.seq()).isEqualTo(1);
        assertThat(debit.side()).isEqualTo(EntrySide.DEBIT);
        assertThat(debit.accountId()).isEqualTo(SOURCE);
        assertThat(debit.balanceAfter()).isEqualByComparingTo(SOURCE_AFTER);
        assertThat(credit.seq()).isEqualTo(2);
        assertThat(credit.side()).isEqualTo(EntrySide.CREDIT);
        assertThat(credit.accountId()).isEqualTo(DESTINATION);
        assertThat(credit.balanceAfter()).isEqualByComparingTo(DEST_AFTER);
        assertThat(debit.transferId()).isEqualTo(TRANSFER);
        assertThat(credit.transferId()).isEqualTo(TRANSFER);
        assertThat(debit.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(credit.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(debit.correlationId()).isEqualTo(CORRELATION);
        assertThat(credit.correlationId()).isEqualTo(CORRELATION);
    }
}