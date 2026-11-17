package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import java.util.Map;
import java.util.Optional;

public interface ApprovalStore {
    ApprovalRequest create(String targetType, String targetId, Map<String, Object> payload, long makerId, String correlationId);
    Optional<ApprovalRequest> find(long id);
    ApprovalRequest lock(long id);
    ApprovalRequest setPayload(long id, Map<String, Object> payload);
    ApprovalRequest decide(long id, boolean approve, long checker, String note);
}
