package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-derived approval visibility for {@code API-APR-01/02} (07 §8.8.3, DEC-ADM-009).
 *
 * <p>The actor always sees the requests they made. A checker additionally sees every approval of the
 * request types they are permitted to decide ({@code ROLE_CHANGE} with {@code ROLE_MANAGE},
 * {@code LIMIT_CHANGE} with {@code LIMIT_MAKER}/{@code LIMIT_MANAGE}). The set is never supplied by the
 * client and is applied inside the SQL predicate, so filters, sorting and counts cannot bypass it.</p>
 *
 * @param actorUserId     trusted authenticated staff id
 * @param reviewableTypes types this checker may review; empty for non-checkers
 */
public record ApprovalVisibility(long actorUserId, Set<ApprovalRequestType> reviewableTypes) {

    public ApprovalVisibility {
        if (actorUserId <= 0) {
            throw new IllegalArgumentException("actorUserId must be positive");
        }
        Objects.requireNonNull(reviewableTypes, "reviewableTypes is required");
        EnumSet<ApprovalRequestType> copy = EnumSet.noneOf(ApprovalRequestType.class);
        copy.addAll(reviewableTypes);
        reviewableTypes = Collections.unmodifiableSet(copy);
    }

    /** True when the actor may see approvals of other makers for this type. */
    public boolean canReview(ApprovalRequestType requestType) {
        return reviewableTypes.contains(requestType);
    }

    /** True when the actor is limited to the requests they made themselves. */
    public boolean isMakerOnly() {
        return reviewableTypes.isEmpty();
    }
}
