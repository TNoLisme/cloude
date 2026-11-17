package com.simulatedbank.transfercandidate.web;

import java.time.Instant;

/** POST success / GET base receipt schema (API contracts §7.2.11). Nullable fields serialize as null. */
public record TransferResponse(String reference, String status, String amount, String currency, String description,
                               AccountRef sourceAccount, AccountRef destinationAccount, String failureCode,
                               String failureMessage, String correlationId, Instant createdAt, Instant completedAt,
                               Instant failedAt) {
}