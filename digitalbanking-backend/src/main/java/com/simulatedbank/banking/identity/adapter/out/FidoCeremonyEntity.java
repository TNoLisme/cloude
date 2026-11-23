package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Ceremony;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_challenge", schema = "banking")
public class FidoCeremonyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "challenge_ref", nullable = false, updatable = false)
    private UUID challengeRef;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Column(name = "device_id", updatable = false)
    private Long deviceId;

    @Column(name = "fido_credential_id", length = 255, updatable = false)
    private String credentialId;

    @Column(name = "fido_nonce", length = 86, updatable = false)
    private String nonce;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private short attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private short maxAttempts;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at", insertable = false, updatable = false)
    private Instant consumedAt;

    @Column(name = "purpose", nullable = false, length = 32, updatable = false)
    private String purpose;

    @Column(name = "method", nullable = false, length = 16, updatable = false)
    private String method;

    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    protected FidoCeremonyEntity() {
    }

    FidoCeremonyEntity(long userId, long deviceId, String credentialId, String nonce,
            Instant expiresAt, int maxAttempts, String correlationId) {
        this.challengeRef = UUID.randomUUID();
        this.userId = userId;
        this.deviceId = deviceId;
        this.credentialId = credentialId;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
        if (maxAttempts <= 0 || maxAttempts > Short.MAX_VALUE) {
            throw new IllegalArgumentException("FIDO max attempts exceeds schema bounds");
        }
        this.maxAttempts = (short) maxAttempts;
        this.correlationId = correlationId;
        this.status = "CREATED";
        this.purpose = "LOGIN_NEW_DEVICE";
        this.method = "FIDO";
    }

    Ceremony snapshot() {
        return new Ceremony(challengeId, challengeRef.toString(), userId, deviceId == null ? 0 : deviceId,
                credentialId, nonce, status, attemptCount, maxAttempts, expiresAt, consumedAt, purpose, method);
    }

    @Override
    public String toString() { return "FidoCeremonyEntity[redacted]"; }
}
