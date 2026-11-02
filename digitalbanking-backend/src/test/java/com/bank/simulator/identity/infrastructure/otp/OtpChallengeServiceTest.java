package com.bank.simulator.identity.infrastructure.otp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class OtpChallengeServiceTest {

    @Test
    void wrongAttemptsReachFiveAndValidCodeConsumesOnce() {
        FakeRepository repository = new FakeRepository();
        OtpChallengeService service = new OtpChallengeService(repository, new OtpHashingService(),
                message -> repository.code = message.code(), fixedClock());
        OtpChallengeService.OtpIssueResult issued = service.issue("0912345678", "SMS", "REGISTRATION");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(service.consume(issued.challengeId(), "0912345678", "SMS", "REGISTRATION", "000000"))
                    .isEqualTo(OtpChallengeService.ConsumeResult.INVALID);
        }
        assertThat(service.consume(issued.challengeId(), "0912345678", "SMS", "REGISTRATION", "000000"))
                .isEqualTo(OtpChallengeService.ConsumeResult.ATTEMPTS_EXCEEDED);
        assertThat(service.consume(issued.challengeId(), "0912345678", "SMS", "REGISTRATION", repository.code))
                .isEqualTo(OtpChallengeService.ConsumeResult.ATTEMPTS_EXCEEDED);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
    }

    private static final class FakeRepository implements OtpChallengeRepository {
        private final Map<UUID, OtpChallengeRecord> records = new ConcurrentHashMap<>();
        private String code;

        @Override
        public void create(UUID id, String identifier, String channel, String purpose, String otpHash,
                           Instant createdAt, Instant expiresAt, int maxAttempts) {
            records.put(id, new OtpChallengeRecord(id, identifier, channel, purpose, otpHash, 0,
                    maxAttempts, expiresAt, null, null));
        }

        @Override
        public OtpChallengeRecord lockChallenge(UUID id, String identifier, String channel, String purpose, Instant now) {
            return records.get(id);
        }

        @Override
        public OtpChallengeRecord lockLatestActive(String identifier, String channel, String purpose, Instant now) {
            return records.values().stream()
                    .filter(record -> record.identifier().equalsIgnoreCase(identifier)
                            && record.channel().equals(channel) && record.purpose().equals(purpose)
                            && record.consumedAt() == null && record.invalidatedAt() == null
                            && record.expiresAt().isAfter(now))
                    .findFirst().orElse(null);
        }

        @Override
        public void incrementAttempts(UUID id, int attempts, Instant invalidatedAt) {
            OtpChallengeRecord record = records.get(id);
            records.put(id, new OtpChallengeRecord(record.id(), record.identifier(), record.channel(), record.purpose(),
                    record.otpHash(), attempts, record.maxAttempts(), record.expiresAt(), record.consumedAt(), invalidatedAt));
        }

        @Override
        public void invalidate(UUID id, Instant invalidatedAt) {
            OtpChallengeRecord record = records.get(id);
            records.put(id, new OtpChallengeRecord(record.id(), record.identifier(), record.channel(), record.purpose(),
                    record.otpHash(), record.attempts(), record.maxAttempts(), record.expiresAt(), record.consumedAt(), invalidatedAt));
        }

        @Override
        public void invalidateActive(String identifier, String channel, String purpose, Instant invalidatedAt) {
            records.values().stream()
                    .filter(record -> record.identifier().equalsIgnoreCase(identifier)
                            && record.channel().equals(channel) && record.purpose().equals(purpose)
                            && record.consumedAt() == null && record.invalidatedAt() == null)
                    .map(OtpChallengeRecord::id).toList()
                    .forEach(id -> invalidate(id, invalidatedAt));
        }

        @Override
        public boolean consume(UUID id, Instant consumedAt) {
            OtpChallengeRecord record = records.get(id);
            if (record == null || record.consumedAt() != null || record.invalidatedAt() != null) return false;
            records.put(id, new OtpChallengeRecord(record.id(), record.identifier(), record.channel(), record.purpose(),
                    record.otpHash(), record.attempts(), record.maxAttempts(), record.expiresAt(), consumedAt, record.invalidatedAt()));
            return true;
        }
    }
}
