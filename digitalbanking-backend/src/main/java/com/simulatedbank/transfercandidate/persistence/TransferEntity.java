package com.simulatedbank.transfercandidate.persistence;

import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** Transfer-owned mapping. Explicit activation is required after its migration and provider prerequisites. */
@Entity
@Table(schema = "banking", name = "transfer")
public class TransferEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id") private Long id;
    @Column(name = "reference_no", nullable = false, length = 32, updatable = false) private String reference;
    @Column(name = "customer_id", nullable = false, updatable = false) private long customerId;
    @Column(name = "initiated_by_user_id", nullable = false, updatable = false) private long initiatedByUserId;
    @Column(name = "source_account_id", nullable = false, updatable = false) private long sourceAccountId;
    @Column(name = "destination_account_id", nullable = false, updatable = false) private long destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(length = 255, updatable = false) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TransferStatus status;
    @Column(name = "failure_code", length = 32) private String failureCode;
    @Column(name = "failure_message", length = 255) private String failureMessage;
    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false) private String correlationId;
    @Column(name = "step_up_challenge_id", updatable = false) private Long stepUpChallengeId;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Generated(event = EventType.INSERT) @Column(name = "updated_at", nullable = false, insertable = false) private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "failed_at") private Instant failedAt;
    @Column(name = "attempt_epoch", nullable = false) private long attemptEpoch;
    @Column(name = "attempt_token") private UUID attemptToken;
    @Column(name = "attempt_owner", length = 128) private String attemptOwner;
    @Column(name = "attempt_lease_expires_at") private Instant attemptLeaseExpiresAt;
    @Column(name = "attempt_state", nullable = false, length = 24) private String attemptState;
    @Column(name = "recovery_attempt_count", nullable = false) private int recoveryAttemptCount;
    @Column(name = "recovery_first_attempt_at") private Instant recoveryFirstAttemptAt;
    @Column(name = "recovery_next_attempt_at") private Instant recoveryNextAttemptAt;

    protected TransferEntity() { }

    /** Creates a pending transfer only; ownership and guard are checked by the caller. */
    public static TransferEntity pending(TransferIdentity identity, TransferAmount amount, String description, String correlationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(amount, "amount");
        if (correlationId == null || correlationId.isBlank() || correlationId.codePointCount(0, correlationId.length()) > 64
                || (description != null && description.codePointCount(0, description.length()) > 255)) {
            throw new IllegalArgumentException("Invalid transfer metadata");
        }
        TransferEntity entity = new TransferEntity();
        entity.reference = identity.reference();
        entity.customerId = identity.customerId();
        entity.initiatedByUserId = identity.initiatedByUserId();
        entity.sourceAccountId = identity.sourceAccountId();
        entity.destinationAccountId = identity.destinationAccountId();
        entity.stepUpChallengeId = identity.stepUpChallengeId();
        entity.amount = amount.value();
        entity.currency = amount.currency();
        entity.description = description;
        entity.correlationId = correlationId;
        entity.status = TransferStatus.PENDING;
        entity.attemptState = "IDLE";
        return entity;
    }

    public Long id() { return id; }
    public String reference() { return reference; }
    public long customerId() { return customerId; }
    public long initiatedByUserId() { return initiatedByUserId; }
    public long sourceAccountId() { return sourceAccountId; }
    public long destinationAccountId() { return destinationAccountId; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public TransferStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    @Override public String toString() { return "TransferEntity[redacted]"; }
}
