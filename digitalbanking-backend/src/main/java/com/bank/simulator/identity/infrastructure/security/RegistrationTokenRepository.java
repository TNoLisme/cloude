package com.bank.simulator.identity.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class RegistrationTokenRepository {
    private final JdbcTemplate jdbc;
    public RegistrationTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Transaction-scoped lock also works before a user row exists. */
    public void lockPhone(String phone) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "registration:" + phone);
    }
    public void create(UUID id, String phone, String hash, Instant now, Instant expiresAt) {
        jdbc.update("INSERT INTO registration_verification_tokens(id, phone, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, phone, hash, Timestamp.from(now), Timestamp.from(expiresAt));
    }
    public void invalidate(String phone, Instant now) {
        jdbc.update("UPDATE registration_verification_tokens SET invalidated_at = ? WHERE phone = ? AND consumed_at IS NULL AND invalidated_at IS NULL", Timestamp.from(now), phone);
    }
    public boolean consume(String phone, String hash, Instant now) {
        return jdbc.update("""
            UPDATE registration_verification_tokens SET consumed_at = ?
            WHERE phone = ? AND token_hash = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
            """, Timestamp.from(now), phone, hash, Timestamp.from(now)) == 1;
    }
}
