package com.bank.simulator.identity.application;

import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class PinCredentialService {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final IdentityJdbcRepository repository;
    private final PinHashingService hashing;
    private final Clock clock;

    public PinCredentialService(IdentityJdbcRepository repository, PinHashingService hashing, Clock clock) {
        this.repository = repository;
        this.hashing = hashing;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChangeResult change(UUID customerId, String currentPin, String newPin) {
        Instant now = clock.instant();
        IdentityJdbcRepository.PinRecord record = repository.lockPin(customerId);
        if (record == null || record.pinHash() == null) return ChangeResult.NOT_SET;
        if (record.lockedUntil() != null && record.lockedUntil().isAfter(now)) return ChangeResult.LOCKED;
        if (!hashing.matches(currentPin, record.pinHash())) {
            int attempts = record.failedAttempts() + 1;
            boolean locked = attempts >= MAX_FAILURES;
            repository.updatePinFailure(customerId, locked ? MAX_FAILURES : attempts,
                    locked ? now.plus(LOCK_DURATION) : null, now);
            return locked ? ChangeResult.LOCKED : ChangeResult.INVALID;
        }
        repository.setPin(customerId, hashing.encode(newPin), now);
        return ChangeResult.CHANGED;
    }

    public enum ChangeResult { CHANGED, INVALID, LOCKED, NOT_SET }
}
