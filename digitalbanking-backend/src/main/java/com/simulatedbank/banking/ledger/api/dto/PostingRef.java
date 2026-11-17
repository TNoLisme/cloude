package com.simulatedbank.banking.ledger.api.dto;

/**
 * The two generated entry IDs of a verified balanced pair: debit (seq 1, source) and credit
 * (seq 2, destination). Both are positive and distinct. Confirms the posting phase within T2 only.
 */
public record PostingRef(long debitEntryId, long creditEntryId) {
}