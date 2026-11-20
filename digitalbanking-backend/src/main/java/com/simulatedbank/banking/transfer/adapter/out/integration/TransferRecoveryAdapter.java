package com.simulatedbank.banking.transfer.adapter.out.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simulatedbank.banking.audit.api.TransferAuditEvidence;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.limit.api.TransferLimits.Missing;
import com.simulatedbank.banking.limit.api.TransferLimits.Recorded;
import com.simulatedbank.banking.limit.api.TransferLimits.ReservationState;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate.Incident;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate.Ports;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Basis;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Completion;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Entry;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Limit;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.LimitState;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Movement;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Replay;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.ReplayProof;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Side;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal SYSTEM worker adapter. Controllers and request DTOs cannot supply recovery authority. */
final class TransferRecoveryAdapter implements Ports {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRecoveryAdapter.class);
    private static final Set<String> RECEIPT_FIELDS = Set.of("reference", "status", "amount", "currency", "description",
            "sourceAccount", "destinationAccount", "failureCode", "failureMessage", "correlationId", "createdAt",
            "completedAt", "failedAt");
    private static final Set<String> MASKED_ACCOUNT_FIELDS = Set.of("accountId", "accountNumberMasked", "accountNumberLast4");

    private final TransferSettlementHandoff handoff;
    private final TransferAuditEvidence auditReads;
    private final ObjectMapper mapper;

    TransferRecoveryAdapter(TransferSettlementHandoff handoff, TransferAuditEvidence auditReads, ObjectMapper mapper) {
        this.handoff = handoff;
        this.auditReads = auditReads;
        this.mapper = mapper;
    }

    @Override
    public void requireSystemRecovery() {
        // Job wiring is internal; require a server-created trace context for the batch.
        if (RequestContextHolder.current().isEmpty()) throw new IllegalStateException("Recovery trace required");
    }

    @Override
    public boolean tryLockCustomerGuard(long customerId) {
        handoff.store.setTimeouts(handoff.lockMs, handoff.statementMs);
        return handoff.limits.tryLockStepUpGuard(customerId).isPresent();
    }

    @Override
    public void lockCustomerGuard(long customerId) {
        handoff.store.setTimeouts(handoff.lockMs, handoff.statementMs);
        handoff.limits.lockStepUpGuard(customerId);
    }

    @Override
    public Optional<Basis> findTransferBasis(Intent intent) {
        return handoff.claims.findBasis(intent.id());
    }

    @Override
    public List<Movement> movements(long transferId) {
        return handoff.movements.findMovement(transferId).stream()
                .map(m -> new Movement(m.transferId(), m.transferId(), m.sourceId(), m.destinationId(), m.amount(),
                        m.currency(), true, true, m.sourceAfter().availableBalance(), m.destinationAfter().availableBalance(),
                        m.sourceAfter().ledgerBalance(), m.destinationAfter().ledgerBalance()))
                .toList();
    }

    @Override
    public List<Entry> entries(long transferId) {
        var details = handoff.store.read(transferId);
        return handoff.ledger.findEvidence(transferId).stream().map(e -> {
            if (!details.intent().correlationId().equals(e.correlationId())) {
                throw new IllegalStateException("Ledger correlation does not match the Transfer intent");
            }
            return new Entry(e.entryId(), e.transferId(), e.sequence(), Side.valueOf(e.side()), e.accountId(), e.amount(),
                    e.currency(), e.balanceAfter());
        }).toList();
    }

    @Override
    public List<Limit> limits(Intent intent, Basis basis) {
        var guard = handoff.limits.lockStepUpGuard(intent.customerId());
        var evidence = handoff.limitAdapter.readEvidence(guard, intent, basis).orElseThrow();
        if (evidence instanceof Missing) return List.of();
        if (!(evidence instanceof Recorded recorded)) {
            throw new IllegalStateException("Limit evidence does not match the Transfer intent");
        }
        var reservation = recorded.reservation();
        var link = reservation.transfer();
        boolean settled = recorded.state() == ReservationState.SETTLED;
        return List.of(new Limit(link.transferId(), link.customerId(), link.amount(), link.currency(),
                LimitState.valueOf(recorded.state().name()), reservation.policyVersion(), reservation.t1Date(),
                recorded.terminalDate().orElse(null), settled ? reservation.policyVersion() : null,
                settled, recorded.state() == ReservationState.PENDING, recorded.terminalAt().orElse(null)));
    }

    @Override
    public List<Completion> completions(long transferId) {
        var details = handoff.store.read(transferId);
        var intent = details.intent();
        return auditReads.findCompletions(details.reference()).stream().map(a -> {
            if ("TRANSFER_COMPLETED".equals(a.action()) && (!"COMPLETED".equals(a.status()) || !"USER".equals(a.actorType())
                    || !Objects.equals(details.userId(), a.actorId()) || !intent.currency().equals(a.currency())
                    || a.amount() == null || a.amount().compareTo(intent.amount()) != 0)) {
                throw new IllegalStateException("Audit completion does not match the Transfer intent");
            }
            return new Completion(a.id(), transferId, a.correlationId(), a.action(), a.outcome(), a.occurredAt());
        }).toList();
    }

    @Override
    public boolean hasNoResponse(Intent intent) {
        return handoff.claims.hasNoResponse(intent.id());
    }

    @Override
    public Optional<Replay> findSafeReplay(Intent intent) {
        var details = handoff.store.read(intent.id());
        return handoff.claims.originalReceipt(intent.id()).map(json -> {
            try {
                var body = (ObjectNode) mapper.readTree(json);
                if (!RECEIPT_FIELDS.containsAll(body.properties().stream().map(e -> e.getKey()).toList())
                        || !details.reference().equals(body.path("reference").asText())
                        || !intent.correlationId().equals(body.path("correlationId").asText())
                        || !intent.currency().equals(body.path("currency").asText())
                        || !body.path("amount").isTextual()
                        || intent.amount().compareTo(new BigDecimal(body.path("amount").asText())) != 0
                        || !Objects.equals(details.description(), body.path("description").asText(null))
                        || !intent.createdAt().equals(readInstant(body.path("createdAt")))
                        || body.hasNonNull("failureCode") || body.hasNonNull("failureMessage") || body.hasNonNull("failedAt")
                        || !maskedAccount(body.path("sourceAccount"), intent.sourceId())
                        || !maskedAccount(body.path("destinationAccount"), intent.destinationId())) {
                    throw new IllegalStateException("Stored receipt does not match the Transfer intent");
                }
                return new Replay(intent.id(), readInstant(body.path("completedAt")), ReplayProof.ORIGINAL_MASKED, body);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Stored receipt is invalid", failure);
            }
        });
    }

    private Instant readInstant(JsonNode value) throws JsonProcessingException {
        return mapper.treeToValue(value, Instant.class);
    }

    private static boolean maskedAccount(JsonNode value, long accountId) {
        String last4 = value.path("accountNumberLast4").asText();
        return value.isObject() && value.size() == MASKED_ACCOUNT_FIELDS.size()
                && MASKED_ACCOUNT_FIELDS.stream().allMatch(name -> value.path(name).isTextual())
                && Long.toString(accountId).equals(value.path("accountId").asText())
                && last4.matches("[0-9]{4}") && ("**** **** " + last4).equals(value.path("accountNumberMasked").asText());
    }

    @Override
    public IdempotencyRetention.Snapshot terminalRetention() {
        return handoff.ttl.get();
    }

    @Override
    public void settleForReconciliation(Intent intent, Limit reservation, Instant terminalAt, LocalDate terminalDate) {
        throw new IllegalStateException("Recovery needs a committed Limit settlement witness");
    }

    @Override
    public Settlement finalizeFailure(Intent intent, AttemptFence fence, Basis basis, Instant at) {
        var details = handoff.store.read(intent.id());
        var guard = handoff.limits.lockStepUpGuard(intent.customerId());
        var evidence = handoff.limitAdapter.readEvidence(guard, intent, basis).orElseThrow();
        if (!(evidence instanceof Recorded recorded) || recorded.state() != ReservationState.PENDING) {
            throw new IllegalStateException("Tfail requires a proven pending reservation");
        }
        var adapter = new TransferSettlementAdapter(handoff, details);
        adapter.finalizeAbortedCredit(details.context(), fence, recorded.reservation(), at.atZone(handoff.zone).toLocalDate(), at);
        return new Settlement(adapter.preparedOutcome(), adapter.response());
    }

    @Override
    public void flushBusinessWrites() {
        handoff.flush.run();
    }

    @Override
    public void recordReconciled(Intent intent, Instant repairedAt) {
        handoff.audit.recordReconciled(handoff.store.read(intent.id()), repairedAt);
    }

    @Override
    public void alert(long transferId, Incident reason) {
        LOG.atWarn().addKeyValue("transfer_id", Long.toString(transferId))
                .addKeyValue("reason", reason.name()).log("TRANSFER_RECOVERY_INCIDENT");
    }
}
