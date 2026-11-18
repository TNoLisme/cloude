package com.simulatedbank.banking.ledger.domain;

/**
 * Internal contract violation: a posting command failed domain validation before any INSERT.
 * The caller (Transfer) already validated the HTTP request, so this is a system error, not a
 * client error; Common maps it to 500 INTERNAL_ERROR. Messages carry no PII or raw values.
 */
public class InvalidPostingCommandException extends RuntimeException {

    public InvalidPostingCommandException(String safeMessage) {
        super(safeMessage);
    }
}