package com.simulatedbank.banking.fraud.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** TRFC-FR-01. Evaluate and persist flags inside the existing Transfer T2 transaction. */
public interface FraudEvaluation {
    void verifyReady();

    Decision evaluateTransfer(Facts facts);

    record Facts(long transferId, long customerId, long sourceId, long destinationId,
                 BigDecimal amount, String currency, Instant terminalAt, String correlationId) {
        public Facts {
            if (transferId <= 0 || customerId <= 0 || sourceId <= 0 || destinationId <= 0
                    || sourceId == destinationId || amount == null || amount.signum() <= 0
                    || !"VND".equals(currency) || terminalAt == null
                    || correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new IllegalArgumentException("Invalid durable Fraud facts");
            }
        }
    }

    enum Outcome { ALLOW, BLOCK, ENGINE_ERROR }

    record Flag(long id, String ruleCode, int ruleVersion, String severity) { }

    record Decision(Outcome outcome, List<Flag> flags) {
        public Decision {
            Objects.requireNonNull(outcome);
            flags = List.copyOf(flags);
            if ((outcome == Outcome.BLOCK && flags.isEmpty())
                    || (outcome == Outcome.ENGINE_ERROR && !flags.isEmpty())) {
                throw new IllegalArgumentException("Invalid Fraud decision");
            }
        }
    }
}
