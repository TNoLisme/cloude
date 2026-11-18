package com.simulatedbank.banking.ledger.domain;

import java.math.BigDecimal;

/** Validated, normalized posting facts; created before the first INSERT (BE-LED-03 §5.2, §7.2). */
public record ValidatedPosting(long transferId, long sourceId, long destinationId, Money amount, String currency,
                               Money sourceBalanceAfter, Money destinationBalanceAfter, String correlationId) {

    private static final String CORRELATION_PATTERN = "[A-Za-z0-9._:-]{1,64}";

    public static ValidatedPosting of(long transferId, long sourceId, long destinationId, BigDecimal amount,
            String currency, BigDecimal sourceBalanceAfter, BigDecimal destinationBalanceAfter, String correlationId) {
        if (transferId <= 0) {
            throw new InvalidPostingCommandException("transferId must be positive");
        }
        if (sourceId <= 0 || destinationId <= 0) {
            throw new InvalidPostingCommandException("sourceId and destinationId must be positive");
        }
        if (sourceId == destinationId) {
            throw new InvalidPostingCommandException("sourceId and destinationId must differ");
        }
        Money validatedAmount = Money.amount(amount);
        Money sourceAfter = Money.balance(sourceBalanceAfter);
        Money destinationAfter = Money.balance(destinationBalanceAfter);
        if (!"VND".equals(currency)) {
            throw new InvalidPostingCommandException("currency must be exactly VND");
        }
        if (correlationId == null || !correlationId.matches(CORRELATION_PATTERN)) {
            throw new InvalidPostingCommandException("correlationId must match [A-Za-z0-9._:-]{1,64}");
        }
        return new ValidatedPosting(transferId, sourceId, destinationId, validatedAmount, currency,
                sourceAfter, destinationAfter, correlationId);
    }

    public EntryDraft debitDraft() {
        return new EntryDraft(transferId, sourceId, 1, EntrySide.DEBIT, amount.value(), currency,
                sourceBalanceAfter.value(), correlationId);
    }

    public EntryDraft creditDraft() {
        return new EntryDraft(transferId, destinationId, 2, EntrySide.CREDIT, amount.value(), currency,
                destinationBalanceAfter.value(), correlationId);
    }
}