package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.domain.RuleType;
import com.simulatedbank.banking.fraud.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fraud_rule_config", schema = "banking")
@IdClass(FraudRuleConfigId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudRuleConfigEntity implements Persistable<FraudRuleConfigId> {

    @Id
    @Column(name = "rule_code", nullable = false, length = 32)
    private String ruleCode;

    @Id
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private RuleType ruleType;

    @Column(name = "threshold_amount", precision = 19, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "threshold_count")
    private Integer thresholdCount;

    @Column(name = "window_seconds")
    private Integer windowSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public FraudRuleConfigId getId() {
        return new FraudRuleConfigId(ruleCode, version);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
