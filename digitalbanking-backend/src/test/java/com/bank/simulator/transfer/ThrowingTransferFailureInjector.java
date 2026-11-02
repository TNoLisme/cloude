package com.bank.simulator.transfer;

import com.bank.simulator.transfer.application.TransferFailureInjector;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("transfer-failure-injection")
public class ThrowingTransferFailureInjector implements TransferFailureInjector {

    @Override
    public void afterDebitBeforeCredit() {
        throw new IllegalStateException("FI-01 injected failure after debit before credit");
    }
}
