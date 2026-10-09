package com.bank.simulator.identity.infrastructure.otp;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "demo"})
public class LocalMailboxOtpSender implements OtpSender {

    private static final int MAX_ENTRIES = 1000;

    private final Map<UUID, MailboxEntry> messages = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocalMailboxOtpSender(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void send(OtpMessage message) {
        evictExpired();
        if (messages.size() >= MAX_ENTRIES && !messages.containsKey(message.challengeId())) {
            throw new IllegalStateException("Local OTP mailbox is full");
        }
        messages.put(message.challengeId(), new MailboxEntry(normalize(message.identifier()), message.code(), message.expiresAt()));
        System.out.println("==================================================");
        System.out.println(">>> [LOCAL OTP] " + message.identifier() + " => MÃ OTP: " + message.code() + " <<<");
        System.out.println("==================================================");
    }

    public String findCode(String identifier) {
        evictExpired();
        String normalized = normalize(identifier);
        return messages.values().stream()
                .filter(entry -> entry.identifier().equals(normalized))
                .map(MailboxEntry::code)
                .findFirst()
                .orElse(null);
    }

    private void evictExpired() {
        var now = clock.instant();
        messages.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String normalize(String identifier) {
        return identifier.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record MailboxEntry(String identifier, String code, java.time.Instant expiresAt) {
    }
}
