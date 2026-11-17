package com.simulatedbank.transfercandidate.persistence;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal adapter for terminal writes. Call only at the terminal phase, after the
 * customer guard and ordered locks are held. All pending business changes are
 * deliberately flushed before the bulk write, and only the Transfer entity is
 * refreshed afterwards. The conditional update is an additional CAS, not a complete
 * fence: before money writes the caller must check the claimed state and valid lease
 * against the primary under the customer guard and transfer lock and hold locks until
 * commit. Token/epoch or expired lease alone cannot prove rollback or resolve an
 * unknown commit. The caller must atomically update idempotency, reservation,
 * outbox and audit in the same transaction. Any non-single-row result throws and marks the transaction rollback-only.
 */
public class TransferTerminalJpaAdapter {
    private final EntityManager entityManager;
    private final TransferJpaRepository repository;

    public TransferTerminalJpaAdapter(EntityManager entityManager, TransferJpaRepository repository) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int completePending(long transferId, long epoch, UUID token, Instant terminalAt) {
        entityManager.flush();
        int count = repository.completePending(transferId, epoch, token, terminalAt);
        requireSingleUpdate(count);
        refreshTransfer(transferId);
        return count;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int failPending(long transferId, long epoch, UUID token, Instant terminalAt, String failureCode) {
        entityManager.flush();
        int count = repository.failPending(transferId, epoch, token, terminalAt, failureCode);
        requireSingleUpdate(count);
        refreshTransfer(transferId);
        return count;
    }

    private static void requireSingleUpdate(int count) {
        if (count != 1) throw new TerminalTransitionRejectedException();
    }

    /** Mismatch is an invariant failure; do not commit or expose database details. */
    public static final class TerminalTransitionRejectedException extends IllegalStateException {
        public TerminalTransitionRejectedException() { super("Transfer terminal transition rejected"); }
    }

    private void refreshTransfer(long id) {
        TransferEntity entity = entityManager.find(TransferEntity.class, id);
        if (entity != null) entityManager.refresh(entity);
    }
}
