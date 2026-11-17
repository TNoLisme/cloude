package com.simulatedbank.banking.ledger.contract;

import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.Entry;
import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.Evidence;
import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.Pair;
import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.Posting;
import static com.simulatedbank.banking.ledger.contract.LedgerContractOracle.Side;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.ledger.application.LedgerPostingService;
import com.simulatedbank.banking.ledger.application.port.LedgerEntryStore;
import com.simulatedbank.banking.ledger.domain.EntryDraft;
import com.simulatedbank.banking.ledger.domain.EntrySide;
import com.simulatedbank.banking.ledger.domain.InvalidPostingCommandException;
import com.simulatedbank.banking.ledger.domain.LedgerInvariantException;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Reuses the BE-LED-01 {@link LedgerContractOracle} vectors against the real provider through a
 * test-only in-memory store. Proves the provider implements the same validation/pair contract as
 * the oracle; propagation/rollback/mapping are proven separately on PostgreSQL (LED-POST-06..13).
 */
class LedgerPostingProviderContractTest {

    private static final Posting POSTING = new Posting(15, 20, 23, new BigDecimal("125.50"), "VND",
            BigDecimal.ZERO, new BigDecimal("325.50"), "transfer:15");

    private final InMemoryStore store = new InMemoryStore();
    private final LedgerPostingService provider = new LedgerPostingService(store);

    @Test
    void providerAcceptsExactlyTheOracleValidPostings() {
        List<Posting> vectors = new ArrayList<>();
        vectors.add(POSTING);
        vectors.add(new Posting(15, 20, 23, new BigDecimal("99999999999999999.99"), "VND",
                BigDecimal.ZERO, new BigDecimal("325.50"), "transfer:15"));
        LedgerContractOracleTest.invalidPostings().forEach(arguments -> vectors.add((Posting) arguments.get()[1]));
        for (String amount : new String[] {"0", "-1.00", "0.001", "1.010", "100000000000000000.00", "1E+17"}) {
            vectors.add(new Posting(15, 20, 23, new BigDecimal(amount), "VND", BigDecimal.ZERO,
                    new BigDecimal("325.50"), "transfer:15"));
        }
        vectors.add(new Posting(15, 20, 23, null, "VND", BigDecimal.ZERO, new BigDecimal("325.50"), "transfer:15"));

        for (Posting vector : vectors) {
            store.reset();
            boolean oracleValid = LedgerContractOracle.valid(vector);
            if (oracleValid) {
                assertThat(provider.postDoubleEntry(command(vector))).isNotNull();
                assertThat(LedgerContractOracle.assess(vector, Optional.of(store.evidence(vector.transferId()))))
                        .isEqualTo(Pair.MATCH);
            } else {
                assertThatThrownBy(() -> provider.postDoubleEntry(command(vector)))
                        .isInstanceOf(InvalidPostingCommandException.class);
                assertThat(store.appended).isZero();
            }
        }
    }

    @Test
    void providerRejectsEveryOracleIncidentReadBack() {
        LedgerContractOracleTest.badEvidence().forEach(arguments -> {
            Object[] args = arguments.get();
            long transferId = (long) args[1];
            @SuppressWarnings("unchecked")
            List<Entry> rows = (List<Entry>) args[2];
            assertThat(LedgerContractOracle.assess(POSTING, Optional.of(new Evidence(transferId, rows))))
                    .isEqualTo(Pair.INCIDENT);

            store.reset();
            store.readBackOverride = rows.stream().map(LedgerPostingProviderContractTest::toPosted).toList();
            // The command itself is valid so the provider reaches the read-back and must reject the pair.
            assertThatThrownBy(() -> provider.postDoubleEntry(command(POSTING)))
                    .isInstanceOf(LedgerInvariantException.class);
        });
    }

    private static PostingCommand command(Posting posting) {
        return new PostingCommand(posting.transferId(), posting.sourceId(), posting.destinationId(),
                posting.amount(), posting.currency(), posting.sourceAfter(), posting.destinationAfter(),
                posting.correlationId());
    }

    private static PostedEntry toPosted(Entry entry) {
        return new PostedEntry(entry.id(), POSTING.transferId(), entry.accountId(), entry.seq(),
                entry.side() == Side.DEBIT ? EntrySide.DEBIT : EntrySide.CREDIT, entry.amount(), entry.currency(),
                entry.balanceAfter(), entry.correlationId());
    }

    /** Test-only in-memory store; assigns synthetic IDs and can override the read-back for incidents. */
    private static final class InMemoryStore implements LedgerEntryStore {

        private final AtomicLong ids = new AtomicLong();
        private final List<PostedEntry> entries = new ArrayList<>();
        private int appended;
        private List<PostedEntry> readBackOverride;

        void reset() {
            ids.set(0);
            entries.clear();
            appended = 0;
            readBackOverride = null;
        }

        Evidence evidence(long transferId) {
            return new Evidence(transferId, entries.stream().map(entry -> new Entry(entry.entryId(), entry.seq(),
                    entry.side() == EntrySide.DEBIT ? Side.DEBIT : Side.CREDIT, entry.accountId(), entry.amount(),
                    entry.currency(), entry.balanceAfter(), entry.correlationId())).toList());
        }

        @Override
        public long append(EntryDraft draft) {
            appended++;
            long id = ids.incrementAndGet();
            entries.add(new PostedEntry(id, draft.transferId(), draft.accountId(), draft.seq(), draft.side(),
                    draft.amount(), draft.currency(), draft.balanceAfter(), draft.correlationId()));
            return id;
        }

        @Override
        public void flush() {
        }

        @Override
        public List<PostedEntry> findAllByTransferIdOrdered(long transferId) {
            if (readBackOverride != null) {
                return readBackOverride;
            }
            return entries.stream()
                    .filter(entry -> entry.transferId() == transferId)
                    .sorted(Comparator.comparingInt(PostedEntry::seq).thenComparingLong(PostedEntry::entryId))
                    .toList();
        }
    }
}