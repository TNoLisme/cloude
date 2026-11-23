package com.simulatedbank.transfercandidate.web;

/** Masked account reference (API contracts §7.2.6); never a full account number. */
public record AccountRef(String accountId, String accountNumberMasked, String accountNumberLast4) {
}