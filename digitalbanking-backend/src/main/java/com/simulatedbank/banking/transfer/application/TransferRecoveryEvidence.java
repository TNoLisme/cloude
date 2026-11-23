package com.simulatedbank.banking.transfer.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Consumer view of the per-transfer evidence contracts in design/05 §13.4. */
public final class TransferRecoveryEvidence {
    private TransferRecoveryEvidence() { }

    public interface Reads {
        // Reads join the primary processing transaction; an empty list means a successful read found no records.
        List<Movement> movements(long transferId);
        List<Entry> entries(long transferId);
        List<Limit> limits(Intent intent, Basis basis);
        List<Completion> completions(long transferId);
    }

    public record Intent(long id, long customerId, long sourceId, long destinationId, BigDecimal amount,
                         String currency, String correlationId, Instant createdAt) {
        private boolean matches(long transferId, BigDecimal value, String unit) {
            return id == transferId && value != null && amount.compareTo(value) == 0 && currency.equals(unit);
        }

        @Override public String toString() { return "Intent[redacted]"; }
    }

    public enum Mode { RESERVATION_REQUIRED, LEGACY }
    public enum LimitState { PENDING, SETTLED, RELEASED, LEGACY_USAGE }
    public enum Side { DEBIT, CREDIT }

    /** Immutable T1 proof from Transfer-owned history, never inferred from evidence absence. */
    public record Basis(Mode mode, String policyVersion, LocalDate t1Date) { }

    public record Movement(long evidenceId, long transferId, long sourceId, long destinationId, BigDecimal amount,
                           String currency, boolean debitApplied, boolean creditApplied, BigDecimal sourceAvailableAfter,
                           BigDecimal destinationAvailableAfter, BigDecimal sourceLedgerAfter,
                           BigDecimal destinationLedgerAfter) { }

    public record Entry(long entryId, long transferId, int sequence, Side side, long accountId, BigDecimal amount,
                        String currency, BigDecimal balanceAfter) { }

    /** Usage flags and date/version are per transfer, established by the Limit owner. */
    public record Limit(long transferId, long customerId, BigDecimal amount, String currency, LimitState state,
                        String policyVersion, LocalDate t1Date, LocalDate usageDate, String usagePolicyVersion,
                        boolean usageApplied, boolean noPriorUsageProven, Instant settledAt) { }

    public record Completion(long auditId, long transferId, String correlationId, String action, String outcome,
                             Instant occurredAt) {
        @Override public String toString() { return "Completion[redacted]"; }
    }

    public enum ReplayProof { ORIGINAL_MASKED, PROVEN_UNSENT_EXACT }

    /** The Transfer response owner attests masking, linkage and replay accuracy. */
    public record Replay(long transferId, Instant terminalAt, ReplayProof proof, ObjectNode body) {
        public Replay {
            body = body.deepCopy();
        }
        @Override public ObjectNode body() { return body.deepCopy(); }
        @Override public String toString() { return "Replay[redacted]"; }
    }

    record Snapshot(List<Movement> movements, List<Entry> entries, List<Limit> limits, List<Completion> audits) {
        static Snapshot read(Reads reads, Intent intent, Basis basis) {
            return new Snapshot(List.copyOf(reads.movements(intent.id())), List.copyOf(reads.entries(intent.id())),
                    List.copyOf(reads.limits(intent, basis)), List.copyOf(reads.completions(intent.id())));
        }

        boolean allowsResume(Intent intent, Basis basis) {
            if (!movements.isEmpty() || !entries.isEmpty() || !audits.isEmpty() || limits.size() != 1) {
                return false;
            }
            Limit limit = limits.getFirst();
            return basis.mode() == Mode.RESERVATION_REQUIRED && matchesLimit(intent, basis, limit)
                    && pendingWithoutUsage(limit);
        }

        Optional<Repair> repair(Intent intent, Basis basis, Instant now, ZoneId businessZone) {
            if (movements.size() != 1 || audits.size() != 1 || entries.size() != 2 || limits.size() != 1) {
                return Optional.empty();
            }
            Movement movement = movements.getFirst();
            Completion audit = audits.getFirst();
            Limit limit = limits.getFirst();
            if (!matchesMovement(intent, movement) || !matchesCompletion(intent, audit, now)
                    || !matchesLimit(intent, basis, limit) || entries.getFirst().entryId() == entries.getLast().entryId()
                    || entries.stream().noneMatch(entry -> matchesEntry(intent, entry, 1, Side.DEBIT,
                            intent.sourceId(), movement.sourceLedgerAfter()))
                    || entries.stream().noneMatch(entry -> matchesEntry(intent, entry, 2, Side.CREDIT,
                            intent.destinationId(), movement.destinationLedgerAfter()))) {
                return Optional.empty();
            }
            LocalDate terminalDate = audit.occurredAt().atZone(businessZone).toLocalDate();
            if (basis.mode() == Mode.RESERVATION_REQUIRED && pendingWithoutUsage(limit)) {
                return Optional.of(new Repair(audit.occurredAt(), terminalDate, Optional.of(limit)));
            }
            boolean settled = basis.mode() == Mode.RESERVATION_REQUIRED
                    ? limit.state() == LimitState.SETTLED : limit.state() == LimitState.LEGACY_USAGE;
            if (!settled || !limit.usageApplied() || limit.noPriorUsageProven()
                    || !audit.occurredAt().equals(limit.settledAt()) || !terminalDate.equals(limit.usageDate()) || !basis.policyVersion().equals(limit.usagePolicyVersion())) {
                return Optional.empty();
            }
            return Optional.of(new Repair(audit.occurredAt(), terminalDate, Optional.empty()));
        }

        private static boolean matchesLimit(Intent intent, Basis basis, Limit limit) {
            return basis.mode() != null && basis.t1Date() != null && version(basis.policyVersion())
                    && intent.matches(limit.transferId(), limit.amount(), limit.currency())
                    && intent.customerId() == limit.customerId()
                    && basis.policyVersion().equals(limit.policyVersion()) && basis.t1Date().equals(limit.t1Date());
        }

        private static boolean pendingWithoutUsage(Limit limit) {
            return limit.state() == LimitState.PENDING && !limit.usageApplied() && limit.noPriorUsageProven()
                    && limit.usageDate() == null && limit.usagePolicyVersion() == null && limit.settledAt() == null;
        }

        private static boolean matchesMovement(Intent intent, Movement movement) {
            return movement.evidenceId() > 0 && intent.matches(movement.transferId(), movement.amount(), movement.currency())
                    && intent.sourceId() == movement.sourceId() && intent.destinationId() == movement.destinationId()
                    && movement.debitApplied() && movement.creditApplied() && balance(movement.sourceAvailableAfter())
                    && balance(movement.destinationAvailableAfter()) && balance(movement.sourceLedgerAfter())
                    && balance(movement.destinationLedgerAfter());
        }

        private static boolean matchesEntry(Intent intent, Entry entry, int seq, Side side, long account, BigDecimal after) {
            return entry.entryId() > 0 && entry.sequence() == seq && entry.side() == side && entry.accountId() == account
                    && intent.matches(entry.transferId(), entry.amount(), entry.currency())
                    && entry.balanceAfter() != null && after.compareTo(entry.balanceAfter()) == 0;
        }

        private static boolean matchesCompletion(Intent intent, Completion audit, Instant now) {
            return audit.auditId() > 0 && audit.transferId() == intent.id() && intent.correlationId().equals(audit.correlationId())
                    && "TRANSFER_COMPLETED".equals(audit.action()) && "SUCCESS".equals(audit.outcome())
                    && audit.occurredAt() != null && !audit.occurredAt().isBefore(intent.createdAt())
                    && !audit.occurredAt().isAfter(now);
        }

        private static boolean balance(BigDecimal value) {
            return value != null && value.signum() >= 0 && value.scale() <= 2;
        }

        private static boolean version(String value) {
            return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 128;
        }
    }

    record Repair(Instant terminalAt, LocalDate terminalDate, Optional<Limit> reservationToSettle) {
        boolean acceptsReplay(Intent intent, Replay replay) {
            return intent.id() == replay.transferId() && terminalAt.equals(replay.terminalAt())
                    && replay.proof() != null && "COMPLETED".equals(replay.body().path("status").asText());
        }
    }
}
