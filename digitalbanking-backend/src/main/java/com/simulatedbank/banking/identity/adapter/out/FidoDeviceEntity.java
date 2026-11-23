package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Credential;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Narrow projection: enrollment, trust and device lifecycle are not writable through this entity. */
@Entity
@Table(name = "device", schema = "banking")
public class FidoDeviceEntity {
    @Id
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "user_id", updatable = false)
    private long userId;

    @Column(name = "device_ref", updatable = false)
    private UUID deviceRef;

    @Column(name = "fido_credential_id", length = 255, updatable = false)
    private String credentialId;

    @Column(name = "fido_public_key_cose", columnDefinition = "text", updatable = false)
    private String publicKeyCose;

    @Column(name = "fido_user_handle", length = 86, updatable = false)
    private String userHandle;

    @Column(name = "fido_sign_count", nullable = false)
    private long signCount;

    @Column(name = "trusted", updatable = false)
    private boolean trusted;

    @Column(name = "status", length = 16, updatable = false)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FidoDeviceEntity() {
    }

    Credential snapshot() {
        return new Credential(deviceId, userId, deviceRef.toString(), credentialId, publicKeyCose,
                userHandle, signCount, trusted, status);
    }

    void advanceCounter(long next, Instant changedAt) {
        if (next < signCount || next > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid FIDO counter advancement");
        }
        signCount = next;
        updatedAt = changedAt;
    }

    @Override
    public String toString() { return "FidoDeviceEntity[redacted]"; }
}
