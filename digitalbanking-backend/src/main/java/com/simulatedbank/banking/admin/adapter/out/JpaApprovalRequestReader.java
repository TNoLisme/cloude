package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestReader;
import com.simulatedbank.banking.admin.application.query.ApprovalCriteria;
import com.simulatedbank.banking.admin.application.query.ApprovalDetailProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalSortDirection;
import com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import com.simulatedbank.banking.admin.domain.ChangePayload;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * JPA read adapter for {@code API-APR-01}/{@code API-APR-02} persistence.
 *
 * <p>The caller owns the read-only transaction ({@code ApprovalQueryService}); this adapter does not
 * open one. List queries use a constructor projection without the payload; detail rows decode the
 * typed payload and apply the approved masking before returning it. The actor scope from
 * {@link ApprovalVisibility} is part of the SQL predicate (and of the count query), never an in-memory
 * post-filter.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
class JpaApprovalRequestReader implements ApprovalRequestReader {

    private static final String SECONDARY_SORT = "approvalId";

    private final ApprovalRequestJpaRepository repository;
    private final AdminPayloadJsonCodec codec;

    JpaApprovalRequestReader(ApprovalRequestJpaRepository repository, AdminPayloadJsonCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    public ApprovalPage findPage(ApprovalCriteria criteria) {
        Sort.Direction direction = criteria.sort().direction() == ApprovalSortDirection.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Only allowlisted properties are used; approvalId is the deterministic tie-breaker required
        // for stable offset pagination when several approvals share created_at.
        Sort sort = Sort.by(direction, criteria.sort().field().persistenceProperty())
                .and(Sort.by(direction, SECONDARY_SORT));
        Page<ApprovalSummaryProjection> page = criteria.visibility().isMakerOnly()
                ? repository.findSummariesForMaker(criteria.requestType(), criteria.status(), criteria.makerId(),
                        criteria.checkerId(), criteria.from(), criteria.to(),
                        criteria.visibility().actorUserId(),
                        PageRequest.of(criteria.page(), criteria.size(), sort))
                : repository.findSummariesForReviewer(criteria.requestType(), criteria.status(),
                        criteria.makerId(), criteria.checkerId(), criteria.from(), criteria.to(),
                        criteria.visibility().actorUserId(),
                        criteria.visibility().reviewableTypes(),
                        PageRequest.of(criteria.page(), criteria.size(), sort));
        return new ApprovalPage(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    public Optional<ApprovalDetailProjection> findDetail(long approvalId, ApprovalVisibility visibility) {
        Optional<ApprovalDetailRow> row = visibility.isMakerOnly()
                ? repository.findDetailRowForMaker(approvalId, visibility.actorUserId())
                : repository.findDetailRowForReviewer(approvalId, visibility.actorUserId(),
                        visibility.reviewableTypes());
        return row.map(this::toDetail);
    }

    private ApprovalDetailProjection toDetail(ApprovalDetailRow row) {
        ChangePayload payload = codec.decode(row.requestType(), row.payload());
        if (payload.targetType() != row.targetType() || !payload.targetId().equals(row.targetId())) {
            throw new IllegalStateException("Stored approval target does not match its payload");
        }
        Map<String, Object> publicPayload = codec.publicPayload(payload);
        ApprovalSummaryProjection summary = new ApprovalSummaryProjection(
                row.approvalId(), row.requestType(), row.targetType(), row.targetId(), row.status(),
                row.makerId(), row.checkerId(), row.createdAt(), row.decidedAt(), row.appliedAt(),
                row.decisionNote(), row.correlationId());
        return new ApprovalDetailProjection(summary, publicPayload);
    }
}
