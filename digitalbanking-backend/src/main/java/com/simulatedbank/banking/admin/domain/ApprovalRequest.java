package com.simulatedbank.banking.admin.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ApprovalRequest(long approvalId, String requestType, String targetType, String targetId,
        Map<String, Object> payload, String status, long makerId, Long checkerId, String decisionNote,
        String correlationId, Instant createdAt, Instant decidedAt, Instant appliedAt) {
    public ApprovalRequest { payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload)); }
}
