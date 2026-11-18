package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import java.time.Instant;
import java.util.Objects;

/**
 * Persistence projection for one {@code ApprovalSummary} row (07 §7.2.14).
 *
 * <p>It deliberately excludes the JSONB {@code payload} and carries actor identifiers instead of
 * resolved display names: list queries must not load payloads, and {@code ActorSummary.displayName}
 * is an Identity-owned lookup that belongs to the application/API layer. It is not a REST DTO.</p>
 */
public record ApprovalSummaryProjection(
        long approvalId,
        ApprovalRequestType requestType,
        ApprovalTargetType targetType,
        String targetId,
        ApprovalStatus status,
        long makerId,
        Long checkerId,
        Instant createdAt,
        Instant decidedAt,
        Instant appliedAt,
        String decisionNote,
        String correlationId) {

    public ApprovalSummaryProjection {
        if (approvalId <= 0 || makerId <= 0) {
            throw new IllegalArgumentException("Stored approval identifiers must be positive");
        }
        Objects.requireNonNull(requestType, "requestType is required");
        Objects.requireNonNull(targetType, "targetType is required");
        Objects.requireNonNull(targetId, "targetId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (checkerId != null && checkerId <= 0) {
            throw new IllegalArgumentException("Stored checker identifier must be positive");
        }
    }
}
