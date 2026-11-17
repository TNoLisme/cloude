package com.simulatedbank.banking.identity.application;

import java.util.Optional;
import java.util.UUID;

/** Optional login device association; metadata and existing trust never replace MFA. */
public interface DeviceRepository {

    record DeviceSnapshot(long deviceId, long userId, UUID deviceRef, String fingerprintHash,
            boolean trusted, String status) {
    }

    Optional<DeviceSnapshot> findOwned(UUID deviceRef, long userId);

    /** Return the same-owner fingerprint row, including revoked rows, without changing its trust or status. */
    DeviceSnapshot registerUntrusted(long userId, String fingerprintHash, String deviceName);

    /** JOIN caller transaction; retain the device row lock until session completion commits. */
    Optional<DeviceSnapshot> lockOwned(long deviceId, long userId);

    /** JOIN caller transaction; never reactivates or trusts a device. */
    boolean markSeen(long deviceId, long userId);
}
