package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.domain.FlagStatus;
import com.simulatedbank.banking.fraud.domain.ReviewDecision;
import com.simulatedbank.banking.fraud.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "fraud_flag", schema = "banking")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudFlagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flag_id")
    private Long flagId;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "rule_code", nullable = false, length = 32)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FlagStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_value", columnDefinition = "jsonb")
    private Map<String, Object> matchedValue;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 16)
    private ReviewDecision decision;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = FlagStatus.OPEN;
        }
    }
}
