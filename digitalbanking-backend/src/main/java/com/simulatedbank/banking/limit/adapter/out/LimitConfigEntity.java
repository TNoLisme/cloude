package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.api.LimitConfiguration.Status;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

@Entity
@Table(name = "limit_config", schema = "banking")
@Getter @Setter @NoArgsConstructor @DynamicInsert
class LimitConfigEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "limit_id") private Long limitId;
    @Column(name = "customer_id", nullable = false) private long customerId;
    @Column(name = "account_id") private Long accountId;
    @Column(name = "per_transaction_limit", nullable = false, precision = 19, scale = 2) private BigDecimal perTransactionLimit;
    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2) private BigDecimal dailyLimit;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
    @Column(name = "effective_to") private Instant effectiveTo;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(nullable = false) private int version;
    @Column(name = "created_by", nullable = false) private long createdBy;
    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "approval_id") private Long approvalId;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "updated_at") private Instant updatedAt;
}
