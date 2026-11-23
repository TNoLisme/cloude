package com.simulatedbank.banking.notification.adapter.out.persistence;

import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import com.simulatedbank.banking.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "notification", schema = "banking")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "auth_challenge_id")
    private Long authChallengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 32)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "template_code", nullable = false, length = 32)
    private String templateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    // The validated outbox payload is immutable. Hibernate must not dirty-check its JSON
    // snapshot as an update when a caller commits after taking the Audit chain lock.
    @Column(name = "payload_masked", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payloadMasked;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "claim_token", length = 64)
    private String claimToken;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "claim_version", nullable = false)
    private long claimVersion;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = NotificationStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
