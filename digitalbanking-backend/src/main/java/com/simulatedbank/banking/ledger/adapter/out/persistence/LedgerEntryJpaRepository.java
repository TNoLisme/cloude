package com.simulatedbank.banking.ledger.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Internal ledger repository; the posting port exposes only append/flush/read, never update/delete. */
@Repository
public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryEntity, Long> {

    /** Reads every entry of the transfer from the database after flush, ordered by seq then ID. */
    @Query(value = "SELECT entry_id AS entryId, transfer_id AS transferId, account_id AS accountId, "
            + "entry_seq AS entrySeq, entry_type AS entryType, amount AS amount, currency AS currency, "
            + "balance_after AS balanceAfter, correlation_id AS correlationId "
            + "FROM banking.ledger_entry WHERE transfer_id = :transferId ORDER BY entry_seq, entry_id",
            nativeQuery = true)
    List<LedgerEntryRow> findRowsByTransferId(@Param("transferId") long transferId);
}