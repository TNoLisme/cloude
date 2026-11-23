package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.DeviceRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Device metadata persistence on the existing V2 schema; no persistent trust creation. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcDeviceRepository implements DeviceRepository {

    private static final String COLUMNS =
            "device_id, user_id, device_ref, device_fingerprint_hash, trusted, status";

    private static final RowMapper<DeviceSnapshot> MAPPER = (rs, rowNum) -> new DeviceSnapshot(
            rs.getLong("device_id"), rs.getLong("user_id"), rs.getObject("device_ref", UUID.class),
            rs.getString("device_fingerprint_hash"), rs.getBoolean("trusted"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public JdbcDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSnapshot> findOwned(UUID deviceRef, long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM banking.device WHERE device_ref = ? AND user_id = ?",
                MAPPER, deviceRef, userId).stream().findFirst();
    }

    @Override
    @Transactional
    public DeviceSnapshot registerUntrusted(long userId, String fingerprintHash, String deviceName) {
        jdbc.update("""
                INSERT INTO banking.device
                    (device_ref, user_id, device_name, device_fingerprint_hash, trusted, status)
                VALUES (?, ?, ?, ?, FALSE, 'ACTIVE')
                ON CONFLICT (user_id, device_fingerprint_hash) DO NOTHING
                """, UUID.randomUUID(), userId, deviceName, fingerprintHash);
        // A separate READ COMMITTED statement sees a concurrent insert after ON CONFLICT waited.
        // Never use an upsert: it could change the status/trust of an existing revoked device.
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM banking.device WHERE user_id = ? AND device_fingerprint_hash = ?",
                MAPPER, userId, fingerprintHash).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Device registration returned no row"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeviceSnapshot> lockOwned(long deviceId, long userId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM banking.device WHERE device_id = ? AND user_id = ? FOR UPDATE",
                MAPPER, deviceId, userId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markSeen(long deviceId, long userId) {
        return jdbc.update("""
                UPDATE banking.device SET last_seen_at = clock_timestamp(), updated_at = clock_timestamp()
                 WHERE device_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, deviceId, userId) == 1;
    }
}
