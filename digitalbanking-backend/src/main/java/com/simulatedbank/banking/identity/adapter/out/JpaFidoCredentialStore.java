package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.FidoCredentialStore;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA CRUD with database row locks; native SQL is confined to wall-clock conditional transitions. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class JpaFidoCredentialStore implements FidoCredentialStore {
    private final FidoDeviceJpaRepository devices;
    private final FidoCeremonyJpaRepository ceremonies;
    private final UserCredentialJpaRepository users;
    private final EntityManager entityManager;
    private final Clock clock;

    public JpaFidoCredentialStore(FidoDeviceJpaRepository devices, FidoCeremonyJpaRepository ceremonies,
            UserCredentialJpaRepository users, EntityManager entityManager, Clock clock) {
        this.devices = devices;
        this.ceremonies = ceremonies;
        this.users = users;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public Optional<Credential> findCredential(String deviceRef, long userId) {
        UUID ref = parseRef(deviceRef);
        return ref == null || userId <= 0 ? Optional.empty()
                : devices.findByDeviceRefAndUserId(ref, userId).map(FidoDeviceEntity::snapshot);
    }

    @Override
    public Optional<Credential> lockCredential(long deviceId, long userId) {
        return devices.lockByIdAndUserId(deviceId, userId).map(device -> {
            // Lock queries may return stale managed objects: refresh after acquiring the database lock.
            entityManager.refresh(device);
            return device.snapshot();
        });
    }

    @Override
    public void lockUser(long userId) {
        var user = users.lockById(userId).orElseThrow(() -> new IllegalStateException("FIDO user not found"));
        entityManager.refresh(user);
    }

    @Override
    public Optional<Ceremony> lockCeremony(String challengeRef) {
        UUID ref = parseRef(challengeRef);
        if (ref == null) {
            return Optional.empty();
        }
        return ceremonies.lockByRef(ref).map(ceremony -> {
            entityManager.refresh(ceremony);
            return ceremony.snapshot();
        });
    }

    @Override
    public Ceremony createCeremony(long userId, long deviceId, String credentialId, String nonce,
            Instant expiresAt, int maxAttempts, String correlationId) {
        return ceremonies.saveAndFlush(new FidoCeremonyEntity(userId, deviceId, credentialId, nonce,
                expiresAt, maxAttempts, correlationId)).snapshot();
    }

    @Override
    public void advanceCounter(long deviceId, long signCount) {
        var device = devices.findById(deviceId).orElseThrow();
        device.advanceCounter(signCount, clock.instant());
        devices.flush();
    }

    @Override
    public boolean verifyCeremony(long challengeId) {
        return lockCeremonyById(challengeId).isPresent() && ceremonies.verify(challengeId) == 1;
    }

    @Override
    public void failCeremony(long challengeId) {
        if (lockCeremonyById(challengeId).isPresent()) {
            ceremonies.fail(challengeId);
        }
    }

    @Override
    public void expireCeremony(long challengeId) {
        if (lockCeremonyById(challengeId).isPresent()) {
            ceremonies.expire(challengeId);
        }
    }

    @Override
    public boolean isLive(long challengeId) {
        return ceremonies.isLive(challengeId);
    }

    private Optional<FidoCeremonyEntity> lockCeremonyById(long challengeId) {
        return ceremonies.lockById(challengeId).map(ceremony -> {
            entityManager.refresh(ceremony);
            return ceremony;
        });
    }

    private static UUID parseRef(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID ref = UUID.fromString(value);
            return ref.toString().equals(value) ? ref : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
