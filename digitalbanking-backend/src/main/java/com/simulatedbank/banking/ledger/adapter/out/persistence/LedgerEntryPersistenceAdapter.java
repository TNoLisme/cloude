package com.simulatedbank.banking.ledger.adapter.out.persistence;

import com.simulatedbank.banking.ledger.application.port.LedgerEntryStore;
import com.simulatedbank.banking.ledger.domain.EntryDraft;
import com.simulatedbank.banking.ledger.domain.EntrySide;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import java.util.List;

/**
 * JPA adapter for the posting persistence port. Inserts run on the caller's transaction-bound
 * connection; IDENTITY assigns IDs at persist time. Read-back uses a scalar native projection so
 * the pair invariant observes actual database rows (including test fault injection), not the
 * persistence context. Spring Data translates SQL failures to DataAccessException for the caller.
 */
public class LedgerEntryPersistenceAdapter implements LedgerEntryStore {

    private final LedgerEntryJpaRepository repository;

    public LedgerEntryPersistenceAdapter(LedgerEntryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public long append(EntryDraft draft) {
        LedgerEntryEntity entity = new LedgerEntryEntity();
        entity.setTransferId(draft.transferId());
        entity.setAccountId(draft.accountId());
        entity.setEntrySeq((short) draft.seq());
        entity.setEntryType(draft.side().name());
        entity.setAmount(draft.amount());
        entity.setCurrency(draft.currency());
        entity.setBalanceAfter(draft.balanceAfter());
        entity.setCorrelationId(draft.correlationId());
        return repository.save(entity).getEntryId();
    }

    @Override
    public void flush() {
        repository.flush();
    }

    @Override
    public List<PostedEntry> findAllByTransferIdOrdered(long transferId) {
        return repository.findRowsByTransferId(transferId).stream()
                .map(row -> new PostedEntry(row.getEntryId(), row.getTransferId(), row.getAccountId(),
                        row.getEntrySeq(), EntrySide.valueOf(row.getEntryType()), row.getAmount(),
                        row.getCurrency(), row.getBalanceAfter(), row.getCorrelationId()))
                .toList();
    }
}