package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import java.util.Optional;

/**
 * Admin-owned persistence boundary for {@code banking.approval_request}.
 *
 * <p>{@link #insert}, {@link #update} and {@link #lockById} must join the caller transaction ({@code MANDATORY})
 * because the approval transition, provider mutation and C2 audit commit together (design/05 §12.1).</p>
 */
public interface ApprovalRequestStore {

    /** Inserts a new PENDING request; {@link ApprovalRequest#approvalId()} must be zero. */
    ApprovalRequest insert(ApprovalRequest request);

    /** Updates a request previously loaded in the same transaction. Callers must lock first. */
    ApprovalRequest update(ApprovalRequest request);

    Optional<ApprovalRequest> findById(long approvalId);

    /** Locks the row with {@code SELECT ... FOR UPDATE}; returns empty when the approval does not exist. */
    Optional<ApprovalRequest> lockById(long approvalId);
}
