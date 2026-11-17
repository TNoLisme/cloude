package com.simulatedbank.banking.admin.adapter.out;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "approval_request", schema = "banking")
@Getter @Setter @NoArgsConstructor @DynamicInsert
class ApprovalEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "approval_id") private Long approvalId;
    @Column(name = "request_type", nullable = false) private String requestType;
    @Column(name = "target_type", nullable = false) private String targetType;
    @Column(name = "target_id", nullable = false) private String targetId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> payload;
    @Column(nullable = false) private String status;
    @Column(name = "maker_id", nullable = false) private long makerId;
    @Column(name = "checker_id") private Long checkerId;
    @Column(name = "decision_note") private String decisionNote;
    @Column(name = "correlation_id", nullable = false) private String correlationId;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "applied_at") private Instant appliedAt;
}
