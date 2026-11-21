package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeIssued;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.OtpDispatchException;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountInactive;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountNotFound;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Completed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Conflict;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Failed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InProgress;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InsufficientBalance;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.LimitExceeded;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Replay;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.SourceNotOwned;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.StepUpRequired;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.ChallengeRequired;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Payload;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.PreflightDenied;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Request;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Result;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settled;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates the preflight, T1, post-commit settlement and out-of-transaction challenge dispatch. */
public final class TransferCreateOrchestrator implements TransferOrchestrator {
    private final TransferPreflightCandidate preflight;
    private final StepUpChallengePort challenges;

    public TransferCreateOrchestrator(TransferPreflightCandidate preflight, StepUpChallengePort challenges) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
    }

    @Override
    public TransferCreateOutcome create(TransferCreateCommand command) {
        Objects.requireNonNull(command, "command");
        TransferActor actor = command.actor();
        TransferCreateInput.Validated input = command.input();
        if (actor.customerId() == null) {
            throw new IllegalStateException("Transfer requires a server-side customer context");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer orchestration requires no outer transaction");
        }
        Request request = new Request(actor.userId(), actor.customerId(), input.idempotencyKey(),
                new Payload(input.sourceAccountId(), input.destination(), input.amount(), input.description()), actor.correlationId());
        try {
            return mapResult(actor, input, preflight.create(request));
        } catch (TransferIntentCandidate.ChallengeRejectedException ex) {
            ErrorCode code = ex.reason() == TransferIntentCandidate.ChallengeRejectedException.Reason.EXPIRED
                    ? ErrorCode.AUTH_OTP_EXPIRED : ErrorCode.STEP_UP_CHALLENGE_MISMATCH;
            throw new BankingBusinessException(code, code.detail(), Map.of(), ex);
        } catch (TransferIntentCandidate.AuditWriteException | TransferSettlementCandidate.AuditWriteException ex) {
            throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.detail(), Map.of(), ex);
        } catch (OtpDispatchException ex) {
            throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED, ErrorCode.OTP_DISPATCH_FAILED.detail(), Map.of(), ex);
        }
    }

    private TransferCreateOutcome mapResult(TransferActor actor, TransferCreateInput.Validated input, Result result) {
        return switch (result) {
            case TransferPreflightCandidate.Conflict ignored -> new Conflict();
            case TransferPreflightCandidate.InProgress ignored -> new InProgress();
            case TransferPreflightCandidate.Replay replay -> new Replay(replay.response().httpStatus(), replay.response().safeBody());
            case PreflightDenied denied -> mapDenial(denied.reason());
            case ChallengeRequired required -> requestStepUp(actor, input, required);
            case Settled settled -> {
                var settlement = settled.settlement();
                if (settlement.committed() != null) yield settlement.committed();
                yield settlement.outcome().failure().isPresent()
                        ? new Failed(settlement.receipt()) : new Completed(settlement.receipt());
            }
        };
    }

    private static TransferCreateOutcome mapDenial(RejectionReason reason) {
        return switch (reason) {
            case ACCOUNT_NOT_FOUND -> new AccountNotFound();
            case ACCOUNT_INACTIVE -> new AccountInactive();
            case SOURCE_NOT_OWNED -> new SourceNotOwned();
            case CUSTOMER_INACTIVE -> new TransferCreateOutcome.CustomerInactive();
            case SAME_ACCOUNT -> throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            case INSUFFICIENT_BALANCE -> new InsufficientBalance();
            case LIMIT_EXCEEDED -> new LimitExceeded();
        };
    }

    /** T0: the provider owns its transaction and dispatch; it returns only after ACK + SENT persist. */
    private TransferCreateOutcome requestStepUp(TransferActor actor, TransferCreateInput.Validated input,
                                                ChallengeRequired required) {
        ChallengeIssued issued = challenges.requestChallenge(new ChallengeRequest(actor.userId(), actor.customerId(),
                StepUpChallengePort.PURPOSE_TRANSFER_STEP_UP, required.requestHash(), input.idempotencyKey(),
                actor.correlationId()));
        return new StepUpRequired(new StepUpChallenge(issued.challengeRef(), StepUpChallengePort.PURPOSE_TRANSFER_STEP_UP,
                issued.methods(), issued.channel(), issued.maskedDestination(), issued.expiresAt(),
                issued.maxAttempts(), issued.remainingAttempts()));
    }
}