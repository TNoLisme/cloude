package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for Admin approval persistence. Mutating methods require an existing caller transaction because the
 * future approval service must commit the approval transition, provider mutation and C2 audit atomically.
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
class JpaApprovalRequestStore implements ApprovalRequestStore {

    private final ApprovalRequestJpaRepository repository;
    private final AdminPayloadJsonCodec codec;

    JpaApprovalRequestStore(ApprovalRequestJpaRepository repository, AdminPayloadJsonCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest insert(ApprovalRequest request) {
        if (request == null || request.approvalId() != 0) {
            throw new IllegalArgumentException("A new approval request must not carry an identifier");
        }
        ApprovalRequestEntity saved = repository.saveAndFlush(
                ApprovalRequestEntity.create(request, codec.encode(request.payload())));
        return toDomain(saved);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest update(ApprovalRequest request) {
        if (request == null || request.approvalId() <= 0) {
            throw new IllegalArgumentException("An existing approval request needs an identifier");
        }
        ApprovalRequestEntity entity = repository.findById(request.approvalId())
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        entity.apply(request, codec.encode(request.payload()));
        // Audit uses JDBC and cannot flush JPA for us. Finish business writes before its chain lock.
        repository.flush();
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalRequest> findById(long approvalId) {
        if (approvalId <= 0) {
            return Optional.empty();
        }
        return repository.findById(approvalId).map(this::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApprovalRequest> lockById(long approvalId) {
        if (approvalId <= 0) {
            return Optional.empty();
        }
        return repository.findByIdForUpdate(approvalId).map(this::toDomain);
    }

    private ApprovalRequest toDomain(ApprovalRequestEntity entity) {
        var payload = codec.decode(entity.getRequestType(), entity.getPayload());
        if (payload.targetType() != entity.getTargetType() || !payload.targetId().equals(entity.getTargetId())) {
            throw new IllegalStateException("Stored approval target does not match its payload");
        }
        return ApprovalRequest.restore(
                entity.getApprovalId(),
                payload,
                entity.getMakerId(),
                entity.getCorrelationId(),
                entity.getCreatedAt(),
                entity.getStatus(),
                entity.getCheckerId(),
                entity.getDecisionNote(),
                entity.getDecidedAt(),
                entity.getAppliedAt());
    }
}
