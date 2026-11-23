package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import com.simulatedbank.banking.limit.application.port.LimitStore;
import com.simulatedbank.banking.limit.application.port.LimitTransferStore;
import com.simulatedbank.banking.limit.application.port.LimitTransferStore.StoredReservation;
import com.simulatedbank.banking.limit.domain.LimitAmounts;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class TransferLimitService implements TransferLimits {
    private final LimitStore limits;
    private final LimitReadPort reads;
    private final LimitTransferStore reservations;
    private final EntityManagerFactory entityManagerFactory;
    private final ZoneId businessZoneId;

    @Override
    @Transactional(readOnly = true)
    public UsageSnapshot getUsageSnapshot(long customerId, Long accountId, Instant asOf, LocalDate date) {
        if (customerId <= 0 || (accountId != null && accountId <= 0) || asOf == null || date == null) {
            throw new IllegalArgumentException("Invalid Limit snapshot context");
        }
        var scopes = reads.findSnapshot(customerId, accountId, date, asOf).stream()
                .map(row -> usage(row.accountId(), row.limitId(), row.version(),
                        row.perTransactionLimit(), row.dailyLimit(), row.usedToday()))
                .toList();
        return new UsageSnapshot(customerId, accountId, asOf, date, scopes);
    }

    @Override
    public Guard lockStepUpGuard(long customerId) {
        return acquireStepUpGuard(customerId, false).orElseThrow();
    }

    @Override
    public Optional<Guard> tryLockStepUpGuard(long customerId) {
        return acquireStepUpGuard(customerId, true);
    }

    private Optional<Guard> acquireStepUpGuard(long customerId, boolean skipBusy) {
        requireWritableTransaction();
        if (customerId <= 0) throw new IllegalArgumentException("Invalid Limit customer");
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof HeldGuard guard && guard.owner == this && !guard.expired) {
                if (guard.customerId != customerId) {
                    throw new IllegalStateException("A Transfer transaction may guard only its source customer");
                }
                return Optional.of(guard);
            }
        }
        if (skipBusy) {
            if (!limits.tryLockCustomerScope(customerId)) return Optional.empty();
        } else {
            limits.lockCustomerScope(customerId);
        }
        var guard = new HeldGuard(customerId);
        TransactionSynchronizationManager.registerSynchronization(guard);
        return Optional.of(guard);
    }

    @Override
    public StepUpDecision evaluateStepUpUnderGuard(Guard token, long sourceAccountId, BigDecimal amount,
            String currency, StepUpPolicy policy) {
        var guard = requireGuard(token);
        requirePayment(guard.customerId, sourceAccountId, amount, currency);
        Objects.requireNonNull(policy, "Step-up policy is required");
        requireVersion(policy.version());
        LimitAmounts.requireValid(policy.singleThreshold(), false);
        LimitAmounts.requireValid(policy.dailyThreshold(), false);

        Instant gateAt = limits.currentTime();
        LocalDate date = businessDate(gateAt);
        configurations(guard.customerId, sourceAccountId, gateAt);
        BigDecimal used = limits.findUsage(guard.customerId, null, date)
                .map(LimitUsage::usedAmount).orElse(BigDecimal.ZERO);
        BigDecimal projected = used.add(reservations.pendingAmount(guard.customerId)).add(amount);
        boolean required = amount.compareTo(policy.singleThreshold()) >= 0
                || projected.compareTo(policy.dailyThreshold()) > 0;
        guard.assessment = new Assessment(sourceAccountId, amount, gateAt, date, policy.version(), required, projected);
        return guard.assessment;
    }

    @Override
    public Reservation reserveForPendingTransfer(Guard token, TransferLink transfer, StepUpDecision decision) {
        var guard = requireGuard(token);
        requireLink(guard, transfer);
        if (!(decision instanceof Assessment assessment) || guard.assessment != assessment
                || assessment.sourceAccountId != transfer.sourceAccountId()
                || assessment.amount.compareTo(transfer.amount()) != 0) {
            throw new IllegalArgumentException("Reservation requires an unused assessment from this transaction");
        }
        var reservation = new Reservation(transfer, assessment.businessDate(), assessment.policyVersion());
        reservations.insertReservation(reservation, assessment.gateAt);
        guard.assessment = null;
        return reservation;
    }

    @Override
    public Optional<Reservation> findPendingReservation(Guard token, TransferLink transfer) {
        var guard = requireGuard(token);
        requireLink(guard, transfer);
        return reservations.findReservation(transfer.transferId()).map(stored -> {
            var reservation = stored.reservation();
            var expected = new Reservation(transfer, reservation.t1Date(), reservation.policyVersion());
            requireMatchingPending(stored, expected);
            return reservation;
        });
    }

    @Override
    public LimitCheck lockAndCheck(Guard token, Reservation expected, Instant terminalAt, LocalDate date) {
        var guard = requireGuard(token);
        var stored = requirePending(guard, expected);
        requireTerminal(stored, terminalAt, date);
        var transfer = expected.transfer();
        var configs = configurations(guard.customerId, transfer.sourceAccountId(), terminalAt);
        var checked = new ArrayList<CheckedScope>(configs.size());
        for (var config : configs) {
            var counter = limits.lockUsage(guard.customerId, config.accountId(), date);
            var snapshot = usage(config.accountId(), config.limitId(), config.version(), config.perTransactionLimit(),
                    config.dailyLimit(), counter.usedAmount());
            checked.add(new CheckedScope(counter, snapshot));
        }
        boolean exceeded = checked.stream().map(CheckedScope::snapshot)
                .anyMatch(scope -> transfer.amount().compareTo(scope.perTransactionLimit()) > 0
                        || transfer.amount().compareTo(scope.remaining()) > 0);
        if (exceeded) return Denial.LIMIT_EXCEEDED;
        var reference = new CheckedUsage(guard, expected, terminalAt, date, List.copyOf(checked));
        return new Allowed(reference, checked.stream().map(CheckedScope::snapshot).toList());
    }

    @Override
    public ReservationState settleOrReleaseReservation(Guard token, Reservation expected, Outcome outcome,
            Instant terminalAt, LocalDate date, Allowed completion) {
        var guard = requireGuard(token);
        Objects.requireNonNull(outcome, "Terminal outcome is required");
        var stored = requirePending(guard, expected);
        requireTerminal(stored, terminalAt, date);
        long transferId = expected.transfer().transferId();
        if (outcome == Outcome.FAILED) {
            if (completion != null) throw new IllegalArgumentException("Release cannot consume a usage reference");
            finish(transferId, ReservationState.RELEASED, terminalAt, date, 0);
            return ReservationState.RELEASED;
        }
        if (completion == null || !(completion.usageRef() instanceof CheckedUsage checked)
                || checked.guard() != guard || !matches(checked.reservation(), expected)
                || !checked.terminalAt().equals(terminalAt) || !checked.date().equals(date)) {
            throw new IllegalArgumentException("Completion requires matching usage locked in this transaction");
        }
        finish(transferId, ReservationState.SETTLED, terminalAt, date, checked.scopes().size());
        for (var scope : checked.scopes()) {
            var snapshot = scope.snapshot();
            BigDecimal amount = expected.transfer().amount();
            limits.incrementUsage(scope.counter(), amount, snapshot.dailyLimit());
            reservations.insertEffect(transferId, new ScopeEffect(
                    snapshot.accountId() == null ? Scope.CUSTOMER : Scope.ACCOUNT, scope.counter().usageId(),
                    snapshot.configId(), snapshot.configVersion(), snapshot.perTransactionLimit(),
                    snapshot.dailyLimit(), amount, 1));
        }
        return ReservationState.SETTLED;
    }

    @Override
    public Evidence findSettlementEvidence(Guard token, Reservation expected, RecoveryActor actor) {
        if (actor != RecoveryActor.SYSTEM) throw new IllegalArgumentException("Recovery requires SYSTEM context");
        var guard = requireGuard(token);
        requireReservation(guard, expected);
        var result = reservations.findReservation(expected.transfer().transferId());
        if (result.isEmpty()) return new Missing();
        var stored = result.get();
        if (!matches(stored.reservation(), expected)) return new Mismatch();
        var effects = reservations.findEffects(expected.transfer().transferId());
        if (!validEvidence(stored, effects)) return new Mismatch();
        if (stored.state() == ReservationState.SETTLED && effects.stream().anyMatch(effect ->
                !matchesUsageScope(expected.transfer(), stored.terminalDate(), effect))) return new Mismatch();
        return new Recorded(stored.reservation(), stored.state(),
                Optional.ofNullable(stored.terminalAt()), Optional.ofNullable(stored.terminalDate()), effects);
    }

    private boolean matchesUsageScope(TransferLink transfer, LocalDate date, ScopeEffect effect) {
        Long accountId = effect.scope() == Scope.CUSTOMER ? null : transfer.sourceAccountId();
        return limits.findUsage(transfer.customerId(), accountId, date)
                .filter(usage -> Objects.equals(usage.usageId(), effect.usageId())
                        && transfer.currency().equals(usage.currency()))
                .isPresent();
    }

    private boolean validEvidence(StoredReservation stored, List<ScopeEffect> effects) {
        if (!businessDate(stored.gateAt()).equals(stored.reservation().t1Date())) return false;
        if (stored.state() == ReservationState.PENDING) {
            return stored.terminalAt() == null && stored.terminalDate() == null
                    && stored.effectCount() == null && effects.isEmpty();
        }
        if (!validTerminal(stored, stored.terminalAt(), stored.terminalDate())
                || !Objects.equals(stored.effectCount(), effects.size())) return false;
        if (stored.state() == ReservationState.RELEASED) return effects.isEmpty();
        var amount = stored.reservation().transfer().amount();
        return !effects.isEmpty() && effects.getFirst().scope() == Scope.CUSTOMER
                && effects.stream().map(ScopeEffect::usageId).distinct().count() == effects.size()
                && effects.stream().allMatch(effect -> effect.amount().compareTo(amount) == 0 && effect.count() == 1
                        && effect.perTransactionLimit().compareTo(amount) >= 0
                        && effect.dailyLimit().compareTo(amount) >= 0);
    }

    private StoredReservation requirePending(HeldGuard guard, Reservation expected) {
        requireReservation(guard, expected);
        var stored = reservations.findReservation(expected.transfer().transferId())
                .orElseThrow(() -> new IllegalStateException("Missing Limit reservation"));
        requireMatchingPending(stored, expected);
        return stored;
    }

    private void requireMatchingPending(StoredReservation stored, Reservation expected) {
        if (!matches(stored.reservation(), expected) || stored.state() != ReservationState.PENDING
                || !validEvidence(stored, reservations.findEffects(expected.transfer().transferId()))) {
            throw new IllegalStateException("Limit reservation is not a matching pending intent");
        }
    }

    private static boolean matches(Reservation stored, Reservation expected) {
        var actual = stored.transfer();
        var link = expected.transfer();
        return actual.transferId() == link.transferId() && actual.customerId() == link.customerId()
                && actual.sourceAccountId() == link.sourceAccountId() && actual.currency().equals(link.currency())
                && actual.createdAt().equals(link.createdAt()) && actual.amount().compareTo(link.amount()) == 0
                && stored.t1Date().equals(expected.t1Date()) && stored.policyVersion().equals(expected.policyVersion());
    }

    private void finish(long id, ReservationState state, Instant time, LocalDate date, int count) {
        if (!reservations.finish(id, state, time, date, count)) {
            throw new IllegalStateException("Limit reservation changed while held");
        }
    }

    private List<LimitConfiguration> configurations(long customerId, long accountId, Instant asOf) {
        var candidates = limits.findTransferConfigurations(customerId, accountId, asOf);
        var effective = candidates.stream()
                .filter(config -> !config.effectiveFrom().isAfter(asOf)
                        && (config.effectiveTo() == null || config.effectiveTo().isAfter(asOf)))
                .filter(config -> config.status() != LimitConfiguration.Status.SUPERSEDED || config.effectiveTo() != null)
                .toList();
        requireUniqueScopes(effective.stream().map(LimitConfiguration::accountId).toList());
        if (effective.stream().noneMatch(config -> config.accountId() == null)) {
            throw new IllegalStateException("Required CUSTOMER limit configuration is unavailable");
        }
        if (effective.stream().noneMatch(config -> config.accountId() != null)
                && candidates.stream().anyMatch(config -> config.accountId() != null
                        && (config.status() == LimitConfiguration.Status.SUPERSEDED
                            || !config.effectiveFrom().isAfter(asOf)))) {
            throw new IllegalStateException("ACCOUNT limit configuration coverage is unavailable");
        }
        return effective;
    }

    private static void requireUniqueScopes(List<Long> scopes) {
        if (scopes.size() != scopes.stream().distinct().count()) {
            throw new IllegalStateException("Overlapping Limit configurations");
        }
    }

    private static ScopeUsage usage(Long accountId, long id, int version, BigDecimal per, BigDecimal daily,
            BigDecimal used) {
        return new ScopeUsage(accountId, id, version, per, daily, used, daily.subtract(used).max(BigDecimal.ZERO));
    }

    private HeldGuard requireGuard(Guard token) {
        requireWritableTransaction();
        if (!(token instanceof HeldGuard guard) || guard.owner != this || guard.expired
                || !TransactionSynchronizationManager.getSynchronizations().contains(guard)) {
            throw new IllegalArgumentException("A Limit guard is required from this transaction");
        }
        return guard;
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().getTransaction().isActive()) {
            throw new IllegalTransactionStateException("Transfer Limit operations require a writable caller transaction");
        }
    }

    private void requireTerminal(StoredReservation stored, Instant terminalAt, LocalDate date) {
        if (!validTerminal(stored, terminalAt, date)) throw new IllegalStateException("Invalid Limit terminal clock or date");
    }

    private boolean validTerminal(StoredReservation stored, Instant terminalAt, LocalDate date) {
        return terminalAt != null && !terminalAt.isBefore(stored.gateAt())
                && !terminalAt.isBefore(stored.reservation().transfer().createdAt())
                && businessDate(terminalAt).equals(date);
    }

    private LocalDate businessDate(Instant instant) {
        return instant.atZone(businessZoneId).toLocalDate();
    }

    private static void requireReservation(HeldGuard guard, Reservation expected) {
        Objects.requireNonNull(expected, "Reservation is required");
        requireLink(guard, expected.transfer());
        Objects.requireNonNull(expected.t1Date(), "T1 date is required");
        requireVersion(expected.policyVersion());
    }

    private static void requireLink(HeldGuard guard, TransferLink transfer) {
        Objects.requireNonNull(transfer, "Transfer is required");
        requirePayment(transfer.customerId(), transfer.sourceAccountId(), transfer.amount(), transfer.currency());
        if (transfer.transferId() <= 0 || transfer.customerId() != guard.customerId || transfer.createdAt() == null) {
            throw new IllegalArgumentException("Transfer does not match the customer guard");
        }
    }

    private static void requirePayment(long customerId, long accountId, BigDecimal amount, String currency) {
        LimitAmounts.requireValid(amount, false);
        if (customerId <= 0 || accountId <= 0 || !"VND".equals(currency)) {
            throw new IllegalArgumentException("Invalid Limit payment context");
        }
    }

    private static void requireVersion(String version) {
        if (version == null || version.isBlank() || version.length() > 128) {
            throw new IllegalArgumentException("Invalid step-up policy version");
        }
    }

    private final class HeldGuard implements Guard, TransactionSynchronization {
        private final TransferLimitService owner = TransferLimitService.this;
        private final long customerId;
        private boolean expired;
        private Assessment assessment;

        private HeldGuard(long customerId) { this.customerId = customerId; }

        @Override
        public void beforeCompletion() { expired = true; }
    }

    private static final class Assessment implements StepUpDecision {
        private final long sourceAccountId;
        private final BigDecimal amount;
        private final Instant gateAt;
        private final LocalDate date;
        private final String version;
        private final boolean required;
        private final BigDecimal projected;

        private Assessment(long sourceAccountId, BigDecimal amount, Instant gateAt, LocalDate date,
                String version, boolean required, BigDecimal projected) {
            this.sourceAccountId = sourceAccountId;
            this.amount = amount;
            this.gateAt = gateAt;
            this.date = date;
            this.version = version;
            this.required = required;
            this.projected = projected;
        }

        @Override public boolean required() { return required; }
        @Override public BigDecimal projectedAmount() { return projected; }
        @Override public String policyVersion() { return version; }
        @Override public LocalDate businessDate() { return date; }
    }

    private record CheckedScope(LimitUsage counter, ScopeUsage snapshot) { }

    private record CheckedUsage(HeldGuard guard, Reservation reservation, Instant terminalAt, LocalDate date,
                                List<CheckedScope> scopes) implements UsageRef { }
}
