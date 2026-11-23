package com.simulatedbank.banking.transfer.adapter.out.limit;

import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.limit.api.TransferLimits.Allowed;
import com.simulatedbank.banking.limit.api.TransferLimits.Guard;
import com.simulatedbank.banking.limit.api.TransferLimits.Reservation;
import com.simulatedbank.banking.limit.api.TransferLimits.StepUpDecision;
import com.simulatedbank.banking.limit.api.TransferLimits.StepUpPolicy;
import com.simulatedbank.banking.limit.api.TransferLimits.TransferLink;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence.PendingTransfer;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Limit calls for the candidate ports. The transaction, locks and activation belong to Transfer. */
@Component
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
public class TransferLimitAdapter {
    private final TransferLimits limits;

    public TransferLimitAdapter(TransferLimits limits) {
        this.limits = limits;
    }

    public Guard lockCustomer(long customerId) {
        return limits.lockStepUpGuard(customerId);
    }

    public Assessment assess(Guard guard, TransferIntentCandidate.Command command, StepUpPolicy policy) {
        var decision = limits.evaluateStepUpUnderGuard(guard, command.identity().sourceAccountId(),
                command.amount().value(), command.amount().currency(), policy);
        return new Assessment(decision);
    }

    public Reservation reserve(Guard guard, TransferIntentCandidate.Command command, PendingTransfer pending,
            Assessment assessment) {
        var link = new TransferLink(pending.id(), command.identity().customerId(),
                command.identity().sourceAccountId(), command.amount().value(), command.amount().currency(),
                pending.createdAt());
        return limits.reserveForPendingTransfer(guard, link, assessment.decision());
    }

    public Check check(Guard guard, Reservation expected, Instant terminalAt, LocalDate date) {
        return new Check(limits.lockAndCheck(guard, expected, terminalAt, date));
    }

    public void finish(Guard guard, Reservation expected, TransferSettlementCandidate.Outcome outcome,
            Instant terminalAt, LocalDate date, Check check) {
        var terminal = outcome.failure().isPresent() ? TransferLimits.Outcome.FAILED : TransferLimits.Outcome.COMPLETED;
        Allowed completion = terminal == TransferLimits.Outcome.COMPLETED && check != null
                && check.result() instanceof Allowed allowed ? allowed : null;
        limits.settleOrReleaseReservation(guard, expected, terminal, terminalAt, date, completion);
    }

    /** Empty means Transfer could not establish a reservation-backed mode and T1 basis. */
    public Optional<TransferLimits.Evidence> readEvidence(Guard guard, TransferRecoveryEvidence.Intent intent,
            TransferRecoveryEvidence.Basis basis) {
        if (basis == null || basis.mode() != TransferRecoveryEvidence.Mode.RESERVATION_REQUIRED
                || basis.t1Date() == null || basis.policyVersion() == null || basis.policyVersion().isBlank()) {
            return Optional.empty();
        }
        var link = new TransferLink(intent.id(), intent.customerId(), intent.sourceId(), intent.amount(),
                intent.currency(), intent.createdAt());
        return Optional.of(limits.findSettlementEvidence(guard, new Reservation(link, basis.t1Date(), basis.policyVersion()),
                TransferLimits.RecoveryActor.SYSTEM));
    }

    public record Assessment(StepUpDecision decision) {
        public TransferIntentCandidate.Assessment candidateView() {
            return new TransferIntentCandidate.Assessment(decision.required(), decision.businessDate(),
                    decision.policyVersion());
        }
    }

    public record Check(TransferLimits.LimitCheck result) {
        public Optional<TransferSettlementCandidate.Failure> failure() {
            return result == TransferLimits.Denial.LIMIT_EXCEEDED
                    ? Optional.of(TransferSettlementCandidate.Failure.LIMIT_EXCEEDED) : Optional.empty();
        }
    }
}
