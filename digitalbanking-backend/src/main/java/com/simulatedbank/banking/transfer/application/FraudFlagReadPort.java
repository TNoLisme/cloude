package com.simulatedbank.banking.transfer.application;

import java.util.List;

/** Fraud-owned flag projection for a transfer; called only for staff holding FRAUD_READ/FRAUD_REVIEW. */
@FunctionalInterface
public interface FraudFlagReadPort {
    List<FraudFlagView> findByTransfer(long transferId);
}