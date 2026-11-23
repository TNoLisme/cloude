package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.simulatedbank.banking.fraud.api.FraudEvaluation;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Flag;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.FraudResult;
import java.time.Instant;

/** Translates durable Transfer facts to the provider-owned TRFC-FR-01 API. */
public final class TransferFraudAdapter {
    private final FraudEvaluation fraud;

    public TransferFraudAdapter(FraudEvaluation fraud) {
        this.fraud = fraud;
    }

    FraudResult evaluate(Intent intent, Instant terminalAt) {
        var decision = fraud.evaluateTransfer(new FraudEvaluation.Facts(intent.id(), intent.customerId(),
                intent.sourceId(), intent.destinationId(), intent.amount(), intent.currency(),
                terminalAt, intent.correlationId()));
        return new FraudResult(decision.outcome() == FraudEvaluation.Outcome.BLOCK, decision.flags().stream()
                .map(flag -> new Flag(flag.id(), flag.ruleCode(), flag.ruleVersion(), flag.severity())).toList());
    }
}
