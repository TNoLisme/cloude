package com.simulatedbank.banking.admin.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.port.ApprovalStore;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class ApprovalJpaAdapter implements ApprovalStore {
    private final ApprovalJpaRepository repository;
    private final ApprovalPersistenceMapper mapper;
    private final EntityManager entityManager;
    private final ObjectMapper json;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest create(String targetType, String targetId, Map<String, Object> payload, long makerId, String correlationId) {
        var entity = new ApprovalEntity();
        entity.setRequestType("LIMIT_CHANGE");
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setPayload(payload);
        entity.setMakerId(makerId);
        entity.setCorrelationId(correlationId);
        entity = repository.save(entity);
        entityManager.refresh(entity);
        return snapshot(entity);
    }
    @Override public Optional<ApprovalRequest> find(long id) {
        return repository.findLimitChange(id).map(this::snapshot);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest lock(long id) {
        return repository.lockLimitChange(id).map(this::snapshot).orElseThrow(() -> new EmptyResultDataAccessException(1));
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest setPayload(long id, Map<String, Object> payload) {
        String encoded;
        try { encoded = json.writeValueAsString(payload); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid approval payload"); }
        if (repository.setPayload(id, encoded) != 1) throw new EmptyResultDataAccessException(1);
        return repository.findLimitChange(id).map(this::snapshot).orElseThrow(() -> new EmptyResultDataAccessException(1));
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest decide(long id, boolean approve, long checker, String note) {
        if (repository.decide(id, approve ? "APPLIED" : "REJECTED", checker, note, approve) != 1)
            throw new EmptyResultDataAccessException(1);
        return repository.findLimitChange(id).map(this::snapshot).orElseThrow(() -> new EmptyResultDataAccessException(1));
    }
    private ApprovalRequest snapshot(ApprovalEntity entity) {
        var value = mapper.toSnapshot(entity);
        entityManager.detach(entity);
        return value;
    }
}
