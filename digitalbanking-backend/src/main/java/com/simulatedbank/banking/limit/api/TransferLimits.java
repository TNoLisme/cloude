package com.simulatedbank.banking.limit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Primary-database Limit operations for Transfer. Callers own Transfer locks, fencing and audit. */
public interface TransferLimits {
    /** Internal read under a server-resolved customer scope, after the caller verifies account ownership. */
    UsageSnapshot getUsageSnapshot(long customerId, Long sourceAccountId, Instant asOf, LocalDate businessDate);

    /** First lock in each gate/T1, T2, Tfail or recovery transaction. */
    Guard lockStepUpGuard(long customerId);

    /** The same guard as T1/T2. Empty means contention; database failures propagate. */
    Optional<Guard> tryLockStepUpGuard(long customerId);

    StepUpDecision evaluateStepUpUnderGuard(Guard guard, long sourceAccountId, BigDecimal amount,
            String currency, StepUpPolicy policy);

    Reservation reserveForPendingTransfer(Guard guard, TransferLink transfer, StepUpDecision decision);

    /** Under a fresh T2 guard after fencing. Empty only when the reservation does not exist. */
    Optional<Reservation> findPendingReservation(Guard guard, TransferLink transfer);

    /** Call after locking accounts. terminalAt must be the primary clock reading taken after those locks. */
    LimitCheck lockAndCheck(Guard guard, Reservation expected, Instant terminalAt, LocalDate businessDate);

    /** COMPLETED requires an Allowed from this transaction. FAILED requires a null completion. */
    ReservationState settleOrReleaseReservation(Guard guard, Reservation expected, Outcome outcome,
            Instant terminalAt, LocalDate businessDate, Allowed completion);

    /** Internal SYSTEM operation after re-reading Transfer and its durable fence. */
    Evidence findSettlementEvidence(Guard guard, Reservation expected, RecoveryActor actor);

    interface Guard { }

    interface StepUpDecision {
        boolean required();
        BigDecimal projectedAmount();
        String policyVersion();
        LocalDate businessDate();
    }

    record StepUpPolicy(String version, BigDecimal singleThreshold, BigDecimal dailyThreshold) {
        public static final StepUpPolicy DEFAULT = new StepUpPolicy(
                "amount-v1", new BigDecimal("10000000.00"), new BigDecimal("20000000.00"));
    }

    record UsageSnapshot(long customerId, Long sourceAccountId, Instant asOf, LocalDate businessDate,
                         List<ScopeUsage> scopes) {
        public UsageSnapshot { scopes = List.copyOf(scopes); }
    }

    record ScopeUsage(Long accountId, long configId, int configVersion, BigDecimal perTransactionLimit,
                      BigDecimal dailyLimit, BigDecimal used, BigDecimal remaining) { }

    record TransferLink(long transferId, long customerId, long sourceAccountId, BigDecimal amount,
                        String currency, Instant createdAt) { }

    record Reservation(TransferLink transfer, LocalDate t1Date, String policyVersion) { }

    sealed interface LimitCheck permits Allowed, Denial { }

    record Allowed(UsageRef usageRef, List<ScopeUsage> scopes) implements LimitCheck {
        public Allowed { scopes = List.copyOf(scopes); }
    }

    enum Denial implements LimitCheck { LIMIT_EXCEEDED }

    interface UsageRef { }

    enum Outcome { COMPLETED, FAILED }

    enum ReservationState { PENDING, SETTLED, RELEASED }

    enum RecoveryActor { SYSTEM }

    enum Scope { CUSTOMER, ACCOUNT }

    record ScopeEffect(Scope scope, long usageId, long configId, int configVersion,
                       BigDecimal perTransactionLimit, BigDecimal dailyLimit,
                       BigDecimal amount, int count) { }

    sealed interface Evidence permits Missing, Mismatch, Recorded { }

    record Missing() implements Evidence { }

    record Mismatch() implements Evidence { }

    record Recorded(Reservation reservation, ReservationState state, Optional<Instant> terminalAt,
                    Optional<LocalDate> terminalDate, List<ScopeEffect> effects) implements Evidence {
        public Recorded { effects = List.copyOf(effects); }
    }
}
