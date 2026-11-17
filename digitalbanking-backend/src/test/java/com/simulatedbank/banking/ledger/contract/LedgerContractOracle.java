package com.simulatedbank.banking.ledger.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Test oracle for TRFC-LE-01/02 and DEC-LED-003, reusable against a future provider. */
final class LedgerContractOracle {

    private LedgerContractOracle() {}

    static boolean valid(Posting posting) {
        return posting.transferId() > 0
                && posting.sourceId() > 0
                && posting.destinationId() > 0
                && posting.sourceId() != posting.destinationId()
                && isMoney(posting.amount(), 1)
                && isMoney(posting.sourceAfter(), 0)
                && isMoney(posting.destinationAfter(), 0)
                && "VND".equals(posting.currency())
                && posting.correlationId() != null
                && posting.correlationId().matches("[A-Za-z0-9._:-]{1,64}");
    }

    static Pair assess(Posting posting, Optional<Evidence> result) {
        if (result.isEmpty()) {
            return Pair.ABSENT;
        }
        Evidence evidence = result.orElseThrow();
        List<Entry> entries = evidence.entries();
        if (evidence.transferId() != posting.transferId() || entries.size() != 2) {
            return Pair.INCIDENT;
        }
        Entry debit = entries.get(0);
        Entry credit = entries.get(1);
        return debit.id() != credit.id()
                && matches(debit, posting, 1, Side.DEBIT, posting.sourceId(), posting.sourceAfter())
                && matches(credit, posting, 2, Side.CREDIT, posting.destinationId(), posting.destinationAfter())
                ? Pair.MATCH : Pair.INCIDENT;
    }

    static Balance assessBalance(long accountId, BigDecimal materialized, Optional<Opening> baseline,
                                 List<Movement> posted) {
        if (baseline.isEmpty()) {
            return Balance.UNPROVEN;
        }
        Opening opening = baseline.orElseThrow();
        if (opening.accountId() != accountId || !isMoney(opening.amount(), 0)
                || !"VND".equals(opening.currency()) || opening.evidenceId().isBlank()
                || posted.stream().anyMatch(entry -> !entry.postedAt().isAfter(opening.effectiveAt()))) {
            return Balance.UNPROVEN;
        }
        BigDecimal expected = opening.amount();
        for (Movement entry : posted) {
            expected = entry.side() == Side.CREDIT ? expected.add(entry.amount()) : expected.subtract(entry.amount());
        }
        return expected.compareTo(materialized) == 0 ? Balance.MATCH : Balance.MISMATCH;
    }

    private static boolean matches(Entry entry, Posting posting, int seq, Side side, long accountId,
                                   BigDecimal balanceAfter) {
        return entry.id() > 0
                && entry.seq() == seq
                && entry.side() == side
                && entry.accountId() == accountId
                && isMoney(entry.amount(), 1) && entry.amount().compareTo(posting.amount()) == 0
                && posting.currency().equals(entry.currency())
                && isMoney(entry.balanceAfter(), 0) && entry.balanceAfter().compareTo(balanceAfter) == 0
                && posting.correlationId().equals(entry.correlationId());
    }

    private static boolean isMoney(BigDecimal value, int minimumSign) {
        return value != null && value.signum() >= minimumSign && value.scale() <= 2
                && (long) value.precision() - value.scale() <= 17;
    }

    enum Side { DEBIT, CREDIT }

    enum Pair { ABSENT, MATCH, INCIDENT }

    enum Balance { UNPROVEN, MATCH, MISMATCH }

    record Posting(long transferId, long sourceId, long destinationId, BigDecimal amount, String currency,
                   BigDecimal sourceAfter, BigDecimal destinationAfter, String correlationId) {}

    record Entry(long id, int seq, Side side, long accountId, BigDecimal amount, String currency,
                 BigDecimal balanceAfter, String correlationId) {}

    record Evidence(long transferId, List<Entry> entries) {
        Evidence {
            entries = List.copyOf(entries);
        }
    }

    record Opening(long accountId, BigDecimal amount, String currency, Instant effectiveAt, String evidenceId) {}

    record Movement(Side side, BigDecimal amount, Instant postedAt) {}
}
