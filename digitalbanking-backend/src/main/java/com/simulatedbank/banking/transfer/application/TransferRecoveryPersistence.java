package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Intent;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Replay;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transfer persistence port. Mutations and row locks join the caller's primary transaction. */
public interface TransferRecoveryPersistence {
    Instant now();
    List<Context> discover(Instant cutoff, Context after, int limit);
    Optional<Locked> lock(Context context);
    AttemptFence claim(Locked locked, String owner, Instant leaseUntil, int count, Instant first, Instant next);
    void markFailurePending(Locked locked);
    void clear(Locked locked, State next, Instant nextAt);
    void complete(Locked locked, Replay replay, Instant repairedAt, IdempotencyRetention.Snapshot ttl);
    int purge(int limit);

    enum State { IDLE, CLAIMED, FAILURE_PENDING, MANUAL, DONE }

    record Locked(Intent intent, String status, State state, long epoch, UUID token, Instant leaseUntil,
                         int count, Instant first, Instant next) {
        public boolean pending() { return "PENDING".equals(status); }

        public boolean matches(Context context) {
            return intent.id() == context.transferId() && intent.customerId() == context.customerId()
                    && intent.createdAt().equals(context.createdAt())
                    && (context.correlationId() == null || intent.correlationId().equals(context.correlationId()));
        }

        public boolean matches(AttemptFence fence) {
            return (state == State.CLAIMED || state == State.FAILURE_PENDING)
                    && epoch == fence.epoch() && fence.token().equals(token);
        }

        @Override public String toString() { return "Locked[redacted]"; }
    }
}
