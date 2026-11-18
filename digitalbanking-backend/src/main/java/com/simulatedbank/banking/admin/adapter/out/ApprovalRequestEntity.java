package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Internal persistence mapping for {@code banking.approval_request}; never expose through module APIs. */
@Entity
@Table(name = "approval_request", schema = "banking")
class ApprovalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_id")
    private Long approvalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 32)
    private ApprovalRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private ApprovalTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ApprovalStatus status;

    @Column(name = "maker_id", nullable = false, updatable = false)
    private Long makerId;

    @Column(name = "checker_id")
    private Long checkerId;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected ApprovalRequestEntity() {
    }

    static ApprovalRequestEntity create(ApprovalRequest request, Map<String, Object> payload) {
        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.apply(request, payload);
        return entity;
    }

    void apply(ApprovalRequest request, Map<String, Object> payload) {
        this.requestType = request.requestType();
        this.targetType = request.targetType();
        this.targetId = request.targetId();
        this.payload = copyPayload(payload);
        this.status = request.status();
        this.makerId = request.makerId();
        this.checkerId = request.checkerId();
        this.decisionNote = request.decisionNote();
        this.correlationId = request.correlationId();
        this.createdAt = request.createdAt();
        this.decidedAt = request.decidedAt();
        this.appliedAt = request.appliedAt();
    }

    Long getApprovalId() {
        return approvalId;
    }

    ApprovalRequestType getRequestType() {
        return requestType;
    }

    ApprovalTargetType getTargetType() {
        return targetType;
    }

    String getTargetId() {
        return targetId;
    }

    Map<String, Object> getPayload() {
        return copyPayload(payload);
    }

    ApprovalStatus getStatus() {
        return status;
    }

    Long getMakerId() {
        return makerId;
    }

    Long getCheckerId() {
        return checkerId;
    }

    String getDecisionNote() {
        return decisionNote;
    }

    String getCorrelationId() {
        return correlationId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    Instant getAppliedAt() {
        return appliedAt;
    }

    private static Map<String, Object> copyPayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    @Override
    public String toString() {
        return "ApprovalRequestEntity[redacted]";
    }
}
