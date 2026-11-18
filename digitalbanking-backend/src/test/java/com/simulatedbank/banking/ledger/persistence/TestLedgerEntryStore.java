package com.simulatedbank.banking.ledger.persistence;

import com.simulatedbank.banking.ledger.application.port.LedgerEntryStore;
import com.simulatedbank.banking.ledger.domain.EntryDraft;
import com.simulatedbank.banking.ledger.domain.EntrySide;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Test-only fault-injecting wrapper around the real persistence adapter. Faults are synchronous
 * hooks (no sleep) used to prove propagation/rollback on PostgreSQL; the production adapter has no
 * fault hooks. {@code readMutator} mocks a projection for read-back corruptions: the append-only
 * trigger and CHECK/unique constraints make real DML corruption of ledger_entry impossible, so the
 * projection is mocked while the two INSERTs and the rollback remain real (BE-LED-03 §9, disclosed).
 */
final class TestLedgerEntryStore implements LedgerEntryStore {

    private final LedgerEntryStore delegate;

    boolean failCreditAppend;
    boolean failFlush;
    boolean failRead;
    UnaryOperator<List<PostedEntry>> readMutator;

    TestLedgerEntryStore(LedgerEntryStore delegate) {
        this.delegate = delegate;
    }

    void reset() {
        failCreditAppend = false;
        failFlush = false;
        failRead = false;
        readMutator = null;
    }

    @Override
    public long append(EntryDraft draft) {
        if (failCreditAppend && draft.side() == EntrySide.CREDIT) {
            throw new DataAccessResourceFailureException("injected credit append failure");
        }
        return delegate.append(draft);
    }

    @Override
    public void flush() {
        delegate.flush();
        if (failFlush) {
            throw new DataAccessResourceFailureException("injected flush failure");
        }
    }

    @Override
    public List<PostedEntry> findAllByTransferIdOrdered(long transferId) {
        if (failRead) {
            throw new DataAccessResourceFailureException("injected read-back failure");
        }
        List<PostedEntry> rows = delegate.findAllByTransferIdOrdered(transferId);
        return readMutator != null ? readMutator.apply(rows) : rows;
    }
}