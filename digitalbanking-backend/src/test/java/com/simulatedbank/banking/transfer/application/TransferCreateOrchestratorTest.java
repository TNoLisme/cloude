package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeIssued;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.OtpDispatchException;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.VerifiedChallenge;
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
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Accepted;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Preflight;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Rejected;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.RejectionReason;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Request;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Stored;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.DestinationType;
import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orchestrator seam mapping with a real preflight candidate and test-only fakes (DEC-TRF-015/016). */
class TransferCreateOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final UUID REF = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String BODY = """
            {"sourceAccountId":"3","destination":{"accountId":"4"},"amount":"1.00"}
            """;

    private final TransferRequestHash hasher = new TransferRequestHash(new ObjectMapper(), new JcsCanonicalizer());
    private final FakePreflightPorts ports = new FakePreflightPorts();
    private final FakeChallengePort challenges = new FakeChallengePort();
    private final TransferCreateOrchestrator orchestrator =
            new TransferCreateOrchestrator(new TransferPreflightCandidate(ports, hasher), challenges);
    private final TransferActor actor = new TransferActor(2, 1L, Set.of("TRANSFER_CREATE"), "corr", "req");
    private final TransferCreateInput.Validated input =
            TransferCreateInput.parse(BODY.getBytes(StandardCharsets.UTF_8), List.of("key"), 65536);
    private final String hash = hasher.hash(1, 3, new Destination(DestinationType.ACCOUNT_ID, "4"),
            new TransferAmount(new BigDecimal("1.00"), "VND"), "");

    @Test
    void completedSettlementMapsToCompletedReceiptWithoutStepUp() {
        ports.reads.add(Optional.empty());
        ports.intent = new TransferIntentCandidate.Created(7);
        ports.settlement = new Settlement(TransferSettlementCandidate.Outcome.completed(), receipt(TransferStatus.COMPLETED, null));
        TransferCreateOutcome outcome = orchestrator.create(new TransferCreateCommand(actor, input));
        assertThat(outcome).isInstanceOf(Completed.class);
        assertThat(((Completed) outcome).view().reference()).isEqualTo("TXN20261116A1B2C3D4");
        assertThat(challenges.lastRequest).isNull();
        assertThat(ports.calls).containsExactly("lookup", "preflight", "T1", "T2");
    }

    @Test
    void failedSettlementMapsToFailedReceipt() {
        ports.reads.add(Optional.empty());
        ports.intent = new TransferIntentCandidate.Created(7);
        ports.settlement = new Settlement(
                TransferSettlementCandidate.Outcome.failed(TransferSettlementCandidate.Failure.INSUFFICIENT_BALANCE),
                receipt(TransferStatus.FAILED, TransferFailureCode.INSUFFICIENT_BALANCE));
        TransferCreateOutcome outcome = orchestrator.create(new TransferCreateCommand(actor, input));
        assertThat(outcome).isInstanceOf(Failed.class);
        assertThat(((Failed) outcome).view().status()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void terminalReplayWinsBeforeAnyAccountRead() {
        ports.reads.add(Optional.of(stored(2, 1, hash, IdempotencyStatus.COMPLETED)));
        TransferCreateOutcome outcome = orchestrator.create(new TransferCreateCommand(actor, input));
        assertThat(outcome).isEqualTo(new Replay(201, "safe body"));
        assertThat(ports.calls).containsExactly("lookup");
    }

    @Test
    void nonterminalRecordMapsToInProgress() {
        ports.reads.add(Optional.of(stored(2, 1, hash, IdempotencyStatus.RECEIVED)));
        assertThat(orchestrator.create(new TransferCreateCommand(actor, input))).isInstanceOf(InProgress.class);
        assertThat(ports.calls).containsExactly("lookup");
    }

    @Test
    void mismatchedActorMapsToConflict() {
        ports.reads.add(Optional.of(stored(9, 1, hash, IdempotencyStatus.COMPLETED)));
        assertThat(orchestrator.create(new TransferCreateCommand(actor, input))).isInstanceOf(Conflict.class);
    }

    @Test
    void preflightDenialsMapToTypedOutcomes() {
        assertDenial(RejectionReason.ACCOUNT_NOT_FOUND, AccountNotFound.class);
        assertDenial(RejectionReason.ACCOUNT_INACTIVE, AccountInactive.class);
        assertDenial(RejectionReason.SOURCE_NOT_OWNED, SourceNotOwned.class);
        assertDenial(RejectionReason.INSUFFICIENT_BALANCE, InsufficientBalance.class);
        assertDenial(RejectionReason.LIMIT_EXCEEDED, LimitExceeded.class);
    }

    @Test
    void challengeRequiredRequestsStepUpBoundToActorHashAndKeyAfterGate() {
        ports.reads.add(Optional.empty());
        ports.intent = new TransferIntentCandidate.ChallengeRequired("v1");
        TransferCreateOutcome outcome = orchestrator.create(new TransferCreateCommand(actor, input));
        assertThat(outcome).isInstanceOf(StepUpRequired.class);
        StepUpChallenge challenge = ((StepUpRequired) outcome).challenge();
        assertThat(challenge.challengeId()).isEqualTo(REF);
        assertThat(challenge.purpose()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(challenge.methods()).containsExactly("OTP", "FIDO");
        assertThat(challenges.lastRequest).isEqualTo(new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", hash, "key", "corr"));
        assertThat(ports.calls).containsExactly("lookup", "preflight", "T1");
    }

    @Test
    void otpDispatchFailureMapsToServiceUnavailableWithoutIntent() {
        ports.reads.add(Optional.empty());
        ports.intent = new TransferIntentCandidate.ChallengeRequired("v1");
        challenges.dispatchFail = true;
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED));
        assertThat(ports.calls).containsExactly("lookup", "preflight", "T1");
    }

    @Test
    void challengeMismatchRejectionMapsToConflict() {
        ports.reads.add(Optional.empty());
        ports.intentFailure = new TransferIntentCandidate.ChallengeRejectedException(
                TransferIntentCandidate.ChallengeRejectedException.Reason.MISMATCH);
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    @Test
    void challengeExpiredRejectionMapsToGone() {
        ports.reads.add(Optional.empty());
        ports.intentFailure = new TransferIntentCandidate.ChallengeRejectedException(
                TransferIntentCandidate.ChallengeRejectedException.Reason.EXPIRED);
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_OTP_EXPIRED));
    }

    @Test
    void auditWriteFailureMapsToAuditWriteFailed() {
        ports.reads.add(Optional.empty());
        ports.intentFailure = new TransferIntentCandidate.AuditWriteException();
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUDIT_WRITE_FAILED));
    }

    @Test
    void terminalAuditWriteFailureMapsToSafeAuditProblem() {
        ports.reads.add(Optional.empty());
        ports.intent = new TransferIntentCandidate.Created(10);
        ports.settlementFailure = new TransferSettlementCandidate.AuditWriteException(
                new IllegalStateException("provider details"));
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOfSatisfying(BankingBusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.AUDIT_WRITE_FAILED);
                    assertThat(ex.safeDetail()).doesNotContain("provider details");
                });
    }

    @Test
    void systemFailurePropagatesWithoutBeingSwallowed() {
        ports.reads.add(Optional.empty());
        ports.intentFailure = new IllegalStateException("commit unknown");
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(actor, input)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void requiresServerSideCustomerContext() {
        TransferActor staff = new TransferActor(2, null, Set.of("TRANSFER_CREATE"), "corr", "req");
        assertThatThrownBy(() -> orchestrator.create(new TransferCreateCommand(staff, input)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ports.calls).isEmpty();
    }

    private void assertDenial(RejectionReason reason, Class<? extends TransferCreateOutcome> expected) {
        FakePreflightPorts isolated = new FakePreflightPorts();
        isolated.reads.add(Optional.empty());
        isolated.reads.add(Optional.empty());
        isolated.preflightOverride = new Rejected(reason);
        TransferCreateOrchestrator isolatedOrchestrator =
                new TransferCreateOrchestrator(new TransferPreflightCandidate(isolated, hasher), challenges);
        assertThat(isolatedOrchestrator.create(new TransferCreateCommand(actor, input))).isInstanceOf(expected);
    }

    private static Stored stored(long actorId, long customerId, String hash, IdempotencyStatus status) {
        boolean terminal = status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED;
        return new Stored(new IdempotencyDecision.Existing(actorId, customerId, hash, status),
                terminal ? Optional.of(new TransferPreflightCandidate.ReplayResponse(
                        status == IdempotencyStatus.COMPLETED ? 201 : 422, "safe body")) : Optional.empty());
    }

    private static TransferView receipt(TransferStatus status, TransferFailureCode failureCode) {
        boolean failed = status == TransferStatus.FAILED;
        return new TransferView(7, "TXN20261116A1B2C3D4", status, new TransferAmount(new BigDecimal("1.00"), "VND"), "",
                new MaskedAccount(3, "**** **** 3001", "3001"), new MaskedAccount(4, "**** **** 6789", "6789"),
                "corr", NOW, failed ? null : NOW, failed ? NOW : null, failureCode, failed ? "failed" : null);
    }

    private static final class FakePreflightPorts implements TransferPreflightCandidate.Ports {
        final Queue<Optional<Stored>> reads = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        Preflight preflightOverride;
        TransferIntentCandidate.Result intent;
        RuntimeException intentFailure;
        RuntimeException settlementFailure;
        Settlement settlement;
        final OptionalLong resolved = OptionalLong.of(4);

        @Override public Optional<Stored> findExistingOnPrimary(String key) {
            calls.add("lookup");
            return reads.remove();
        }
        @Override public OptionalLong resolveAccountNumber(String accountNumber) {
            calls.add("resolve");
            return resolved;
        }
        @Override public Preflight preflight(Request request, String hash, long destinationId) {
            calls.add("preflight");
            if (preflightOverride != null) return preflightOverride;
            TransferIdentity identity = new TransferIdentity("R", request.customerId(), request.actorId(),
                    request.payload().sourceAccountId(), destinationId, null);
            return new Accepted(new TransferIntentCandidate.Command(identity, request.payload().amount(),
                    request.payload().description(), "corr", request.idempotencyKey(), hash, 86400, "v1", null));
        }
        @Override public TransferIntentCandidate.Result createIntent(TransferIntentCandidate.Command command) {
            calls.add("T1");
            if (intentFailure != null) throw intentFailure;
            return intent;
        }
        @Override public Settlement settleCreated(long transferId) {
            calls.add("T2");
            if (settlementFailure != null) throw settlementFailure;
            return settlement;
        }
    }

    private static final class FakeChallengePort implements StepUpChallengePort {
        ChallengeRequest lastRequest;
        boolean dispatchFail;
        final ChallengeIssued issued = new ChallengeIssued(REF, "OTP", List.of("OTP", "FIDO"), "SMS",
                "********123", NOW.plusSeconds(300), 5, 5);
        @Override public ChallengeIssued requestChallenge(ChallengeRequest request) {
            lastRequest = request;
            if (dispatchFail) throw new OtpDispatchException();
            return issued;
        }
        @Override public Optional<VerifiedChallenge> findVerifiedBindingIfPresent(ChallengeRequest request) {
            return Optional.empty();
        }
    }
}