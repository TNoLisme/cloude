package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.application.query.ApprovalCriteria;
import com.simulatedbank.banking.admin.application.query.ApprovalDetailProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import java.util.Optional;

/**
 * Admin-owned read boundary for {@code banking.approval_request} (BE-ADM-DB-02).
 *
 * <p>Both operations are pure reads. The application query service owns the read-only transaction,
 * defaulting and validation; implementations only translate already-validated criteria into a
 * projection query and must never load JSONB payloads for list pages.</p>
 */
public interface ApprovalRequestReader {

    /** Offset page of summaries within the actor scope; a page beyond the last returns an empty item list. */
    ApprovalPage findPage(ApprovalCriteria criteria);

    /**
     * Summary + masked payload within the actor scope, or empty for an unknown/inaccessible identifier
     * (anti-enumeration at the API layer).
     */
    Optional<ApprovalDetailProjection> findDetail(long approvalId, ApprovalVisibility visibility);
}
