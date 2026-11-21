package com.simulatedbank.banking.limit.adapter.out;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;

@Entity
@Table(name = "limit_usage", schema = "banking")
@Getter @Setter @NoArgsConstructor @DynamicInsert
class LimitUsageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "usage_id") private Long usageId;
    @Column(name = "customer_id", nullable = false) private long customerId;
    @Column(name = "account_id") private Long accountId;
    @Column(name = "usage_date", nullable = false) private LocalDate usageDate;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @Column(name = "used_amount", nullable = false, precision = 19, scale = 2) private BigDecimal usedAmount;
    @Column(name = "transfer_count", nullable = false) private int transferCount;
    @Column(name = "updated_at") private Instant updatedAt;
}
