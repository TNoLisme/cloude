package com.simulatedbank.banking.ledger.application.port;

import com.simulatedbank.banking.ledger.domain.EntryDraft;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import java.util.List;

/**
 * Internal persistence port for the posting use case (append, flush, read-back). Not a public
 * cross-module API. Implementations stay inside the ledger adapter and use the caller's
 * transaction-bound connection; they expose no update/delete of existing entries.
 */
public interface LedgerEntryStore {

    /** Appends one entry and returns its database-generated ID. */
    long append(EntryDraft draft);

    /** Flushes pending inserts so constraints run inside the current transaction. */
    void flush();

    /** Reads ALL entries of the transfer from the database, ordered by entry_seq then entry_id. */
    List<PostedEntry> findAllByTransferIdOrdered(long transferId);
}