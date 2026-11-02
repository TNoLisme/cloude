package com.bank.simulator.transfer.application;

/**
 * Test seam for failure-injection scenarios (FI-01). Production wiring uses a
 * no-op bean; the throwing implementation is registered only under the
 * {@code transfer-failure-injection} profile.
 */
public interface TransferFailureInjector {

    void afterDebitBeforeCredit();
}
