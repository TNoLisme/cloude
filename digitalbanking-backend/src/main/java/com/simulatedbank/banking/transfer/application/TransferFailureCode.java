package com.simulatedbank.banking.transfer.application;

/** Public transfer failure codes from API contracts §7.2.11; distinct from the HTTP ErrorCode catalog. */
public enum TransferFailureCode {
    INSUFFICIENT_BALANCE, LIMIT_EXCEEDED, ACCOUNT_INACTIVE, ACCOUNT_NOT_FOUND, FRAUD_BLOCKED,
    OTP_DISPATCH_FAILED, AUDIT_WRITE_FAILED, INVALID_STATE_TRANSITION, PROCESSING_ERROR
}