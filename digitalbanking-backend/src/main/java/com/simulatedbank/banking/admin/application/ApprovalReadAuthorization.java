package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Read-side authorization for {@code API-APR-01/02} (07 §8.8.3/§8.8.4, DEC-ADM-009).
 *
 * <p>Requires the authenticated {@code OPERATIONS}/{@code ADMIN} staff role and returns the actor id plus
 * the request types they may review. Read-only endpoints never trust a client supplied {@code makerId}/
 * {@code checkerId} for authorization; those remain optional filters applied <em>inside</em> the scope.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalReadAuthorization {

    private final AdminAuthorization authorization;

    public ApprovalReadAuthorization(AdminAuthorization authorization) {
        this.authorization = authorization;
    }

    /** Fail-closed visibility of the current caller; throws 401/403 through {@link AdminAuthorization}. */
    public ApprovalVisibility requireVisibility() {
        long actorUserId = authorization.requireApprovalReader();
        return new ApprovalVisibility(actorUserId, authorization.reviewableRequestTypes());
    }
}
