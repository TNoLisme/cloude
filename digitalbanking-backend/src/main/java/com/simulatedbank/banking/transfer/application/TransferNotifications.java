package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Consumer-side TRFC-NO-01 mapping. The provider owns persistence and all T3 activity. */
public final class TransferNotifications {
    private final RecipientPolicy policy;
    private final Outbox outbox;

    public TransferNotifications(RecipientPolicy policy, Outbox outbox) {
        this.policy = Objects.requireNonNull(policy);
        this.outbox = Objects.requireNonNull(outbox);
    }

    public void enqueue(Facts facts, Outcome outcome) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer notification must join T2");
        }
        var batch = new LinkedHashMap<RecipientChannel, Notice>();
        for (Recipient route : List.copyOf(policy.recipients(facts, outcome))) {
            if (route.customerId() != facts.senderCustomerId() && route.customerId() != facts.receiverCustomerId()) {
                throw new IllegalStateException("Notification recipient is outside the transfer");
            }
            var key = new RecipientChannel(route.customerId(), route.channel());
            var notice = new Notice(facts.transferId(), facts.reference(), facts.amount(), facts.maskedSource(),
                    facts.maskedDestination(), facts.correlationId(), outcome, route);
            Notice previous = batch.putIfAbsent(key, notice);
            if (previous != null && !previous.equals(notice)) {
                throw new IllegalStateException("Conflicting transfer notification policy");
            }
        }
        if (!batch.isEmpty()) outbox.enqueueInT2(List.copyOf(batch.values()));
    }

    @FunctionalInterface
    public interface RecipientPolicy {
        /** Evaluate the supplied facts and local policy only. No contact resolution or gateway I/O in T2. */
        List<Recipient> recipients(Facts facts, Outcome outcome);
    }

    @FunctionalInterface
    public interface Outbox {
        /** JOIN the same transaction, check durable uniqueness and propagate insert/mismatch errors. */
        void enqueueInT2(List<Notice> notices);
    }

    public enum Channel { IN_APP, EMAIL, SMS }

    public record Recipient(long customerId, Channel channel, String templateCode) {
        public Recipient {
            if (customerId <= 0 || templateCode == null || !templateCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("Invalid notification route");
            }
            Objects.requireNonNull(channel);
        }
    }

    /** Built from locked Transfer facts and the Account port's masked projections. */
    public record Facts(long transferId, String reference, long senderCustomerId, long receiverCustomerId,
                        TransferAmount amount, String maskedSource, String maskedDestination, String correlationId) {
        public Facts {
            if (transferId <= 0 || senderCustomerId <= 0 || receiverCustomerId <= 0
                    || reference == null || !reference.matches("TXN[0-9]{8}[A-Z0-9]{8,20}")
                    || !isMasked(maskedSource) || !isMasked(maskedDestination)
                    || !RequestContext.isValidCorrelationId(correlationId)) {
                throw new IllegalArgumentException("Invalid transfer notification facts");
            }
            Objects.requireNonNull(amount);
        }

        private static boolean isMasked(String number) {
            return number != null && number.matches("\\*{4} \\*{4} [0-9]{4}");
        }

        @Override public String toString() { return "Facts[redacted]"; }
    }

    public record Notice(long transferId, String reference, TransferAmount amount, String maskedSource,
                         String maskedDestination, String correlationId, Outcome outcome, Recipient recipient) {
        @Override public String toString() { return "Notice[redacted]"; }
    }

    private record RecipientChannel(long customerId, Channel channel) { }
}
