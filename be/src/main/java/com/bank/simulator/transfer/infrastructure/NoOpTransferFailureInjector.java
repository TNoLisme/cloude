package com.bank.simulator.transfer.infrastructure;

import com.bank.simulator.transfer.application.TransferFailureInjector;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!transfer-failure-injection")
public class NoOpTransferFailureInjector implements TransferFailureInjector {

    @Override
    public void afterDebitBeforeCredit() {
    }
}
