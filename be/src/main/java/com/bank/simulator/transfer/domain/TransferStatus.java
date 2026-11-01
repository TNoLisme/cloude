package com.bank.simulator.transfer.domain;

public enum TransferStatus {
    AWAITING_OTP,
    COMPLETED,
    EXPIRED,
    FAILED;

    public boolean isTerminal() {
        return this != AWAITING_OTP;
    }
}
