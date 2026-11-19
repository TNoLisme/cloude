package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.TransferBinding;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate.Command;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TransferIdentityAdapter implements StepUpChallengePort {
    private final com.simulatedbank.banking.identity.api.StepUpChallengePort identity;

    public TransferIdentityAdapter(com.simulatedbank.banking.identity.api.StepUpChallengePort identity) {
        this.identity = identity;
    }

    @Override
    public ChallengeIssued requestChallenge(ChallengeRequest request) {
        try {
            var view = identity.requestTransferChallenge(binding(request), request.correlationId());
            return new ChallengeIssued(UUID.fromString(view.challengeRef()), view.method(), List.of(view.method()),
                    view.channel(), null, view.expiresAt(), view.maxAttempts(), view.remainingAttempts());
        } catch (BankingBusinessException failure) {
            if (failure.code() == ErrorCode.OTP_DISPATCH_FAILED) {
                throw new OtpDispatchException();
            }
            throw failure;
        }
    }

    @Override
    public Optional<VerifiedChallenge> findVerifiedBindingIfPresent(ChallengeRequest request) {
        return identity.findVerifiedBindingIfPresent(binding(request)).map(view ->
                new VerifiedChallenge(UUID.fromString(view.challengeRef()), view.expiresAt()));
    }

    long consume(Command command, UUID ref) {
        return identity.consumeTransferChallenge(binding(command), ref.toString()).challengeId();
    }

    static ChallengeRequest request(Command command) {
        return new ChallengeRequest(command.identity().initiatedByUserId(), command.identity().customerId(),
                PURPOSE_TRANSFER_STEP_UP, command.requestHash(), command.idempotencyKey(), command.correlationId());
    }

    private static TransferBinding binding(Command command) {
        return binding(request(command));
    }

    private static TransferBinding binding(ChallengeRequest request) {
        return new TransferBinding(request.userId(), request.customerId(), request.requestHash(),
                request.idempotencyKey());
    }
}
