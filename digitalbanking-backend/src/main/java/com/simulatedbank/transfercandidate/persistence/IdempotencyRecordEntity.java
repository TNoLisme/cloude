package com.simulatedbank.transfercandidate.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read mapping only: create/terminal writes must follow the atomic Transfer transaction protocol. */
@Entity
@Table(schema = "banking", name = "idempotency_record")
public class IdempotencyRecordEntity {
    @Id @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false) private String key;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @Column(name = "transfer_id", nullable = false, updatable = false) private long transferId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private IdempotencyStatus status;
    @Column(name = "response_status") private Short responseStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_body", columnDefinition = "jsonb") private JsonNode responseBody;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "ttl_t1_seconds", nullable = false, updatable = false) private long ttlT1Seconds;
    @Column(name = "ttl_t1_config_version", nullable = false, length = 128, updatable = false) private String ttlT1ConfigVersion;
    @Column(name = "ttl_terminal_seconds") private Long ttlTerminalSeconds;
    @Column(name = "ttl_terminal_config_version", length = 128) private String ttlTerminalConfigVersion;

    protected IdempotencyRecordEntity() { }

    public long transferId() { return transferId; }
    public String requestHash() { return requestHash; }
    public IdempotencyStatus status() { return status; }
    public Short responseStatus() { return responseStatus; }
    public JsonNode responseBody() { return responseBody == null ? null : responseBody.deepCopy(); }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }
    public Instant expiresAt() { return expiresAt; }
    @Override public String toString() { return "IdempotencyRecordEntity[redacted]"; }
}
