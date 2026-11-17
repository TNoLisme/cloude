package com.simulatedbank.banking.ledger.domain;

import java.math.BigDecimal;
import java.util.List;

/** Full pair invariant checked on the database read-back before returning success (BE-LED-03 §7.3). */
public final class PairVerifier {

    private PairVerifier() {
    }

    public static void verify(ValidatedPosting posting, List<PostedEntry> entries, long debitEntryId, long creditEntryId) {
        if (entries.size() != 2) {
            throw new LedgerInvariantException("ledger pair must contain exactly 2 entries");
        }
        PostedEntry debit = entries.get(0);
        PostedEntry credit = entries.get(1);

        require(debit.entryId() > 0 && credit.entryId() > 0 && debit.entryId() != credit.entryId(),
                "entry IDs must be positive and distinct");
        require(debit.entryId() == debitEntryId && credit.entryId() == creditEntryId,
                "entry IDs must match the generated debit/credit IDs");
        require(debit.transferId() == posting.transferId() && credit.transferId() == posting.transferId(),
                "entries must belong to the commanded transfer");
        require(debit.seq() == 1 && debit.side() == EntrySide.DEBIT && debit.accountId() == posting.sourceId(),
                "first entry must be seq 1 DEBIT on the source account");
        require(credit.seq() == 2 && credit.side() == EntrySide.CREDIT && credit.accountId() == posting.destinationId(),
                "second entry must be seq 2 CREDIT on the destination account");
        require(posting.sourceId() != posting.destinationId(), "source and destination must differ");

        require(Money.satisfies(debit.amount(), 1) && debit.amount().compareTo(posting.amount().value()) == 0,
                "debit amount must be valid and equal the command amount");
        require(Money.satisfies(credit.amount(), 1) && credit.amount().compareTo(posting.amount().value()) == 0,
                "credit amount must be valid and equal the command amount");
        require("VND".equals(debit.currency()) && debit.currency().equals(posting.currency()),
                "debit currency must be exactly VND");
        require("VND".equals(credit.currency()) && credit.currency().equals(posting.currency()),
                "credit currency must be exactly VND");
        require(credit.amount().subtract(debit.amount()).compareTo(BigDecimal.ZERO) == 0,
                "signed sum -DEBIT + CREDIT must be zero");

        require(Money.satisfies(debit.balanceAfter(), 0)
                        && debit.balanceAfter().compareTo(posting.sourceBalanceAfter().value()) == 0,
                "debit balance_after must equal the source ledger snapshot");
        require(Money.satisfies(credit.balanceAfter(), 0)
                        && credit.balanceAfter().compareTo(posting.destinationBalanceAfter().value()) == 0,
                "credit balance_after must equal the destination ledger snapshot");
        require(posting.correlationId().equals(debit.correlationId())
                        && posting.correlationId().equals(credit.correlationId()),
                "entries must carry the intent correlation");
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) {
            throw new LedgerInvariantException(safeMessage);
        }
    }
}