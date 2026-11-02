package com.bank.simulator.risk.application;

import com.bank.simulator.transfer.application.TransferCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferCommittedRiskListener {
    private static final Logger log = LoggerFactory.getLogger(TransferCommittedRiskListener.class);
    private final RiskEvaluationService risk;

    public TransferCommittedRiskListener(RiskEvaluationService risk) {
        this.risk = risk;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCommitted(TransferCommittedEvent event) {
        try {
            risk.evaluate(new TransferRiskSnapshot(event.transferId(), event.sourceAccountId(), event.amount(),
                    event.completedAt(), event.correlationId()));
        } catch (RuntimeException error) {
            log.warn("Risk evaluation failed after transfer commit: {}", error.getClass().getSimpleName());
        }
    }
}
