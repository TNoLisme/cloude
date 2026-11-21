package com.simulatedbank.banking.fraud.api;

import java.util.List;

/** Staff receipt projection. The caller checks receipt scope and fraud read permission. */
public interface FraudFlagSummaryQuery {
    List<Flag> findByTransfer(long transferId);

    record Flag(long flagId, String ruleCode, int ruleVersion, String severity, String status) {}
}
