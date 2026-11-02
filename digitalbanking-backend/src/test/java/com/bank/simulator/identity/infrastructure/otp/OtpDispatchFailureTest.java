package com.bank.simulator.identity.infrastructure.otp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpDispatchFailureTest {

    @Test
    void senderFailureInvalidatesPersistedChallenge() {
        FakeRepository repository = new FakeRepository();
        OtpChallengeService service = new OtpChallengeService(repository, new OtpHashingService(),
                message -> { throw new IllegalStateException("delivery unavailable"); },
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.issue("0912345678", "SMS", "REGISTRATION"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.records).hasSize(1);
        assertThat(repository.records.values().iterator().next().invalidatedAt())
                .isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    private static final class FakeRepository implements OtpChallengeRepository {
        private final java.util.Map<UUID, OtpChallengeRecord> records = new java.util.HashMap<>();
        @Override public void create(UUID id, String identifier, String channel, String purpose, String hash,
                                      Instant createdAt, Instant expiresAt, int maxAttempts) {
            records.put(id, new OtpChallengeRecord(id, identifier, channel, purpose, hash, 0, maxAttempts, expiresAt, null, null));
        }
        @Override public OtpChallengeRecord lockChallenge(UUID id, String identifier, String channel, String purpose, Instant now) { return records.get(id); }
        @Override public OtpChallengeRecord lockLatestActive(String identifier, String channel, String purpose, Instant now) { return null; }
        @Override public void incrementAttempts(UUID id, int attempts, Instant invalidatedAt) { }
        @Override public void invalidate(UUID id, Instant invalidatedAt) {
            OtpChallengeRecord record = records.get(id);
            records.put(id, new OtpChallengeRecord(record.id(), record.identifier(), record.channel(), record.purpose(),
                    record.otpHash(), record.attempts(), record.maxAttempts(), record.expiresAt(), null, invalidatedAt));
        }
        @Override public void invalidateActive(String identifier, String channel, String purpose, Instant invalidatedAt) { }
        @Override public boolean consume(UUID id, Instant consumedAt) { return true; }
    }
}
