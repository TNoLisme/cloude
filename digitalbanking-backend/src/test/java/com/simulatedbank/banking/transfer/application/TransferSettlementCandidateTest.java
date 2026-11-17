package com.simulatedbank.banking.transfer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Failure;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Flag;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.FraudResult;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Ports;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies consumer orchestration only; mocks do not establish database atomicity. */
class TransferSettlementCandidateTest {
    private static final Instant CREATED = Instant.parse("2026-11-16T15:59:58Z");
    private static final Instant TERMINAL = Instant.parse("2026-11-16T17:00:00Z");
    private static final AttemptFence FENCE = new AttemptFence(4, UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final LocalDate DATE = LocalDate.parse("2026-11-17");
    private final Context context = new Context(3, 7, CREATED);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Ports ports = mock(Ports.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final TransferSettlementCandidate candidate = new TransferSettlementCandidate(manager, ports,
            ZoneId.of("Asia/Ho_Chi_Minh"));

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(status);
        when(ports.readContextOnPrimary(3)).thenReturn(context);
        when(ports.lockPendingAndReadFence(context)).thenReturn(FENCE);
        when(ports.primaryClockTimestamp()).thenReturn(TERMINAL);
        when(ports.validateLockedState(context, DATE)).thenReturn(Optional.empty());
        when(ports.evaluateFraud(context, TERMINAL)).thenReturn(new FraudResult(false, List.of()));
        when(ports.debitConditional(context)).thenReturn(true);
    }

    @Test
    void fraudVersionMustBePositiveInteger() {
        assertEquals(1, new Flag(1, "RULE", 1, "LOW").ruleVersion());
        assertThrows(IllegalArgumentException.class, () -> new Flag(1, "RULE", 0, "LOW"));
        assertThrows(IllegalArgumentException.class, () -> new Flag(1, "RULE", -1, "LOW"));
    }

    @Test
    void engineFailureFailsOpenOnlyAfterSavepointRollbackAndContextProof() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        Object savepoint = new Object();
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(savepoint);
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.fraudContextUsableAfterRollback()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL))
                .thenThrow(new TransferSettlementCandidate.FraudEngineException("engine"));
        assertEquals(Outcome.completed(), candidate.settle(3, FENCE));
        InOrder order = inOrder(txStatus, ports);
        order.verify(txStatus).createSavepoint();
        order.verify(ports).evaluateFraud(context, TERMINAL);
        order.verify(txStatus).rollbackToSavepoint(savepoint);
        order.verify(ports).fraudContextUsableAfterRollback();
        order.verify(txStatus).releaseSavepoint(savepoint);
        order.verify(ports).recordFraudFailOpen();
        order.verify(ports).debitConditional(context);
        verify(ports, never()).recordFraudFlagAudit(any(), any(), any());
    }

    @Test
    void engineFailureWithoutUsableContextRollsBackWholeTransaction() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(new Object());
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL))
                .thenThrow(new TransferSettlementCandidate.FraudEngineException("engine"));
        assertThrows(TransferSettlementCandidate.FraudEngineException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(txStatus);
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).recordFraudFailOpen();
    }

    @Test
    void rollbackOnlyTransactionCannotFailOpen() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(new Object());
        when(txStatus.isRollbackOnly()).thenReturn(true);
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL))
                .thenThrow(new TransferSettlementCandidate.FraudEngineException("engine"));
        assertThrows(TransferSettlementCandidate.FraudEngineException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(txStatus);
        verify(ports, never()).fraudContextUsableAfterRollback();
        verify(ports, never()).debitConditional(any());
    }

    @Test
    void contextProofCannotLeaveTransactionRollbackOnly() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(new Object());
        when(txStatus.isRollbackOnly()).thenReturn(false, true);
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.fraudContextUsableAfterRollback()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL))
                .thenThrow(new TransferSettlementCandidate.FraudEngineException("engine"));
        assertThrows(TransferSettlementCandidate.FraudEngineException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(txStatus);
        verify(ports, never()).debitConditional(any());
    }

    @Test
    void failOpenTelemetryFailureRollsBackWholeTransaction() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(new Object());
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.fraudContextUsableAfterRollback()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL))
                .thenThrow(new TransferSettlementCandidate.FraudEngineException("engine"));
        doThrow(new IllegalStateException("telemetry unavailable")).when(ports).recordFraudFailOpen();
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(txStatus);
        verify(ports, never()).debitConditional(any());
    }

    @Test
    void nonEngineFraudFailureNeverFailsOpen() {
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.createSavepoint()).thenReturn(new Object());
        when(ports.fraudSavepointSupported()).thenReturn(true);
        when(ports.evaluateFraud(context, TERMINAL)).thenThrow(new IllegalStateException("invariant"));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(txStatus);
        verify(ports, never()).recordFraudFailOpen();
        verify(ports, never()).debitConditional(any());
    }

    @Test
    void successUsesLockOrderOneTerminalDateAndAuditAfterBusinessFlush() {
        Flag later = new Flag(9, "RULE", 1, "HIGH");
        Flag earlier = new Flag(2, "RULE", 1, "LOW");
        when(ports.evaluateFraud(context, TERMINAL)).thenReturn(new FraudResult(false, List.of(later, earlier)));
        Outcome outcome = candidate.settle(3, FENCE);
        assertEquals(Outcome.completed(), outcome);
        InOrder order = inOrder(ports, manager);
        order.verify(ports).readContextOnPrimary(3);
        order.verify(manager).getTransaction(any());
        order.verify(ports).lockStepUpGuard(7);
        order.verify(ports).lockPendingAndReadFence(context);
        order.verify(ports).lockAccountsAscending(context);
        order.verify(ports).primaryClockTimestamp();
        order.verify(ports).lockUsageCustomerThenAccount(context, DATE);
        order.verify(ports).validateLockedState(context, DATE);
        order.verify(ports).evaluateFraud(context, TERMINAL);
        order.verify(ports).debitConditional(context);
        order.verify(ports).credit(context);
        order.verify(ports).postAndVerifyBalancedLedger(context);
        order.verify(ports).settleCompletedReservation(context, TERMINAL, DATE);
        order.verify(ports).writeTerminalAndOutbox(context, outcome, TERMINAL);
        order.verify(ports).flushBusinessWrites();
        order.verify(ports).recordFraudFlagAudit(context, earlier, TERMINAL);
        order.verify(ports).recordFraudFlagAudit(context, later, TERMINAL);
        order.verify(ports).recordTerminalAudit(context, outcome, TERMINAL);
        order.verify(manager).commit(status);
        verify(manager, never()).rollback(any());
        verify(ports, never()).releaseFailedReservation(any(), any(), any());
    }

    @Test
    void explicitBusinessFailuresReleaseReservationWithoutMoney() {
        when(ports.validateLockedState(context, DATE)).thenReturn(Optional.of(Failure.LIMIT_EXCEEDED));
        Outcome failure = candidate.settle(3, FENCE);
        assertEquals(Outcome.failed(Failure.LIMIT_EXCEEDED), failure);
        verify(ports, never()).evaluateFraud(any(), any());
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).credit(any());
        verify(ports, never()).postAndVerifyBalancedLedger(any());
        verify(ports).releaseFailedReservation(context, TERMINAL, DATE);
        verify(ports, never()).settleCompletedReservation(any(), any(), any());
        verify(ports).recordTerminalAudit(context, failure, TERMINAL);
        verify(manager).commit(status);
    }

    @Test
    void fraudBlockAndConditionalDebitFailureDoNotCreditOrPostLedger() {
        Flag flag = new Flag(1, "BLOCK", 1, "HIGH");
        when(ports.evaluateFraud(context, TERMINAL)).thenReturn(new FraudResult(true, List.of(flag)));
        Outcome blocked = candidate.settle(3, FENCE);
        assertEquals(Outcome.failed(Failure.FRAUD_BLOCKED), blocked);
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).credit(any());
        verify(ports).recordFraudFlagAudit(context, flag, TERMINAL);
        verify(ports).releaseFailedReservation(context, TERMINAL, DATE);
        verify(ports, never()).settleCompletedReservation(any(), any(), any());
    }

    @Test
    void failedConditionalDebitIsBusinessOutcomeOnlyWhenNoDebitOccurred() {
        when(ports.debitConditional(context)).thenReturn(false);
        Outcome failure = candidate.settle(3, FENCE);
        assertEquals(Outcome.failed(Failure.INSUFFICIENT_BALANCE), failure);
        verify(ports, never()).credit(any());
        verify(ports, never()).postAndVerifyBalancedLedger(any());
        verify(ports).releaseFailedReservation(context, TERMINAL, DATE);
        verify(ports, never()).settleCompletedReservation(any(), any(), any());
    }

    @Test
    void creditExceptionRequestsRollbackAndNeverWritesTerminal() {
        doThrow(new IllegalStateException("provider failed")).when(ports).credit(context);
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        verify(ports, never()).writeTerminalAndOutbox(any(), any(), any());
        verify(ports, never()).recordTerminalAudit(any(), any(), any());
    }

    @Test
    void auditFailureRequestsRollbackAfterAllWrites() {
        doThrow(new IllegalStateException("audit failed")).when(ports).recordTerminalAudit(eq(context), any(), eq(TERMINAL));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(ports).writeTerminalAndOutbox(eq(context), any(), eq(TERMINAL));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void fraudFailureOrInvalidFenceFailsClosedWithoutMoney() {
        when(ports.lockPendingAndReadFence(context)).thenThrow(new IllegalStateException("fence"));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(ports, never()).debitConditional(any());
        verify(manager).rollback(status);
    }

    @Test
    void staleEpochFailsClosedEvenWhenTokenMatches() {
        when(ports.lockPendingAndReadFence(context)).thenReturn(new AttemptFence(5, FENCE.token()));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(ports, never()).lockAccountsAscending(any());
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).settleCompletedReservation(any(), any(), any());
        verify(ports, never()).releaseFailedReservation(any(), any(), any());
    }

    @Test
    void staleTokenFailsClosedEvenWhenEpochMatches() {
        when(ports.lockPendingAndReadFence(context)).thenReturn(new AttemptFence(4,
                UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(ports, never()).lockAccountsAscending(any());
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).writeTerminalAndOutbox(any(), any(), any());
    }

    @Test
    void absentFenceAndInvalidAttemptAreRejectedWithoutMoneyMovement() {
        when(ports.lockPendingAndReadFence(context)).thenReturn(null);
        assertThrows(NullPointerException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(ports, never()).debitConditional(any());
        assertThrows(IllegalArgumentException.class, () -> new AttemptFence(0, FENCE.token()));
        assertThrows(NullPointerException.class, () -> new AttemptFence(4, null));
    }

    @Test
    void outerTransactionIsRejectedBeforeReadingOrTakingLocks() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
            verify(ports, never()).readContextOnPrimary(anyLong());
            verify(manager, never()).getTransaction(any());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void recoveryRequiresAnExistingTransactionBeforeTakingLocks() {
        var recovery = new TransferSettlementCandidate(new FakeTransactionManager(), ports,
                ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThrows(IllegalTransactionStateException.class,
                () -> recovery.settleInRecoveryTransaction(context, FENCE));
        verify(ports, never()).lockStepUpGuard(anyLong());
    }

    @Test
    void recoveryUsesCallerTransactionAndReturnsBeforeCallerCommit() {
        var fakeManager = new FakeTransactionManager();
        var recovery = new TransferSettlementCandidate(fakeManager, ports, ZoneId.of("Asia/Ho_Chi_Minh"));
        new TransactionTemplate(fakeManager).execute(status -> {
            assertEquals(Outcome.completed(), recovery.settleInRecoveryTransaction(context, FENCE));
            assertFalse(fakeManager.committed);
            assertFalse(fakeManager.rolledBack);
            return null;
        });
        assertTrue(fakeManager.committed);
        InOrder order = inOrder(ports);
        order.verify(ports).lockStepUpGuard(7);
        order.verify(ports).lockPendingAndReadFence(context);
        order.verify(ports).lockAccountsAscending(context);
        order.verify(ports).lockUsageCustomerThenAccount(context, DATE);
        order.verify(ports).debitConditional(context);
        order.verify(ports).recordTerminalAudit(eq(context), any(), eq(TERMINAL));
        verify(ports, never()).readContextOnPrimary(anyLong());
    }

    @Test
    void recoveryFailureMarksCallerRollbackOnlyEvenWhenCaught() {
        var fakeManager = new FakeTransactionManager();
        var recovery = new TransferSettlementCandidate(fakeManager, ports, ZoneId.of("Asia/Ho_Chi_Minh"));
        doThrow(new IllegalStateException("credit failure")).when(ports).credit(context);
        assertThrows(UnexpectedRollbackException.class, () -> new TransactionTemplate(fakeManager).execute(status -> {
            assertThrows(IllegalStateException.class, () -> recovery.settleInRecoveryTransaction(context, FENCE));
            return null;
        }));
        assertTrue(fakeManager.rolledBack);
        assertFalse(fakeManager.committed);
        verify(ports, never()).writeTerminalAndOutbox(any(), any(), any());
    }

    /** Simulates Spring participation and rollback-only propagation, not database atomicity. */
    private static final class FakeTransactionManager extends AbstractPlatformTransactionManager {
        private final FakeTransaction transaction = new FakeTransaction();
        private boolean committed;
        private boolean rolledBack;

        @Override protected Object doGetTransaction() { return transaction; }
        @Override protected boolean isExistingTransaction(Object tx) { return ((FakeTransaction) tx).active; }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {
            ((FakeTransaction) tx).active = true;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((FakeTransaction) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object tx) { ((FakeTransaction) tx).active = false; }
    }

    private static final class FakeTransaction implements SmartTransactionObject {
        private boolean active;
        private boolean rollbackOnly;
        @Override public boolean isRollbackOnly() { return rollbackOnly; }
    }

    @Test
    void failedReservationSettlementRollsBackBeforeTerminalAndAudit() {
        doThrow(new IllegalStateException("settlement")).when(ports).settleCompletedReservation(context, TERMINAL, DATE);
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(ports, never()).writeTerminalAndOutbox(any(), any(), any());
        verify(ports, never()).recordTerminalAudit(any(), any(), any());
    }

    @Test
    void fraudFlagAuditFailureRollsBackAndDoesNotWriteTerminalAudit() {
        Flag flag = new Flag(1, "RULE", 1, "LOW");
        when(ports.evaluateFraud(context, TERMINAL)).thenReturn(new FraudResult(false, List.of(flag)));
        doThrow(new IllegalStateException("flag audit")).when(ports).recordFraudFlagAudit(context, flag, TERMINAL);
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(manager).rollback(status);
        verify(ports, never()).recordTerminalAudit(any(), any(), any());
    }

    @Test
    void fraudEngineExceptionFailsClosedWithoutTreatingItAsBusinessFailure() {
        when(ports.evaluateFraud(context, TERMINAL)).thenThrow(new IllegalStateException("fraud unavailable"));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(ports, never()).debitConditional(any());
        verify(ports, never()).writeTerminalAndOutbox(any(), any(), any());
        verify(manager).rollback(status);
    }

    @Test
    void clockBeforeCreationAbortsAndCommitExceptionIsNotTurnedIntoOutcome() {
        when(ports.primaryClockTimestamp()).thenReturn(CREATED.minusSeconds(1));
        assertThrows(IllegalStateException.class, () -> candidate.settle(3, FENCE));
        verify(ports, never()).debitConditional(any());
        verify(manager).rollback(status);
    }

    @Test
    void unknownCommitIsPropagatedWithoutAssumingRollback() {
        doThrow(new TransactionSystemException("commit unknown")).when(manager).commit(status);
        assertThrows(TransactionSystemException.class, () -> candidate.settle(3, FENCE));
        verify(manager, never()).rollback(any());
    }
}
