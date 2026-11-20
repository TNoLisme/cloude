package com.simulatedbank.banking.ledger.api;

import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.ledger.api.dto.PostingRef;
import java.math.BigDecimal;
import java.util.List;

/**
 * Ledger posting port (TRFC-LE-01, Modules contract §9/§13.4). The caller (Transfer) must invoke
 * this through the Spring proxy inside an open read-write T2 on the primary datasource/transaction
 * manager; the provider joins that transaction (MANDATORY), never commits or opens its own.
 */
public interface LedgerPosting {

    /**
     * Appends exactly one DEBIT (seq 1, source) and one CREDIT (seq 2, destination) entry, flushes,
     * reads the whole pair back from the database and verifies every invariant before returning the
     * two generated entry IDs. Any invalid command, missing transaction, persistence or invariant
     * failure propagates so the caller's T2 rolls back. A returned ref confirms the posting phase
     * inside the transaction, not the commit or a COMPLETED transfer.
     */
    PostingRef postDoubleEntry(PostingCommand command);

    /** Internal recovery read, on the same primary transaction as the Transfer fence. */
    List<EntryEvidence> findEvidence(long transferId);

    record EntryEvidence(long entryId, long transferId, int sequence, String side, long accountId,
                         BigDecimal amount, String currency, BigDecimal balanceAfter, String correlationId) { }
}