package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import java.time.Instant;
import java.util.Map;

/**
 * Explicit constructor projection for {@code ApprovalDetail}. Only the canonical columns needed by
 * 07 §7.2.14 are selected; the JSONB payload is decoded and masked inside the adapter before it
 * reaches the application projection.
 */
record ApprovalDetailRow(
        long approvalId,
        ApprovalRequestType requestType,
        ApprovalTargetType targetType,
        String targetId,
        Map<String, Object> payload,
        ApprovalStatus status,
        long makerId,
        Long checkerId,
        String decisionNote,
        String correlationId,
        Instant createdAt,
        Instant decidedAt,
        Instant appliedAt) {
}
