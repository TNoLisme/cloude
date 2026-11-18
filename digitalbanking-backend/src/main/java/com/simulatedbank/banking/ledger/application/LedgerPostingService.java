package com.simulatedbank.banking.ledger.application;

import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.ledger.api.dto.PostingRef;
import com.simulatedbank.banking.ledger.application.port.LedgerEntryStore;
import com.simulatedbank.banking.ledger.domain.InvalidPostingCommandException;
import com.simulatedbank.banking.ledger.domain.PairVerifier;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import com.simulatedbank.banking.ledger.domain.ValidatedPosting;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting use case (BE-LED-03 §7). Joins the caller's T2 via MANDATORY through the Spring proxy;
 * validates the whole command before the first INSERT, appends DEBIT then CREDIT, flushes, reads
 * the full pair back from the database and verifies every invariant before returning the IDs.
 * Not component-scanned: registered explicitly once the production chain/wiring is reviewed
 * (BE-LED-04), following the candidate convention (DEC-TRF-016).
 */
public class LedgerPostingService implements LedgerPosting {

    private final LedgerEntryStore store;

    public LedgerPostingService(LedgerEntryStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostingRef postDoubleEntry(PostingCommand command) {
        if (command == null) {
            throw new InvalidPostingCommandException("posting command must not be null");
        }
        ValidatedPosting posting = ValidatedPosting.of(command.transferId(), command.sourceId(),
                command.destinationId(), command.amount(), command.currency(), command.sourceBalanceAfter(),
                command.destinationBalanceAfter(), command.correlationId());

        long debitEntryId = store.append(posting.debitDraft());
        long creditEntryId = store.append(posting.creditDraft());
        store.flush();
        List<PostedEntry> readBack = store.findAllByTransferIdOrdered(posting.transferId());
        PairVerifier.verify(posting, readBack, debitEntryId, creditEntryId);
        return new PostingRef(debitEntryId, creditEntryId);
    }
}