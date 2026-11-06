package com.bank.simulator.identity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshSessionService {

    private final RefreshSessionRepository repository;
    private final RefreshTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration ttl;
    private final Duration gracePeriod;

    public RefreshSessionService(RefreshSessionRepository repository,
                                 @Value("${app.security.refresh-token-ttl:7d}") Duration ttl,
                                 Clock clock) {
        this(repository, ttl, Duration.ofSeconds(10), clock);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RefreshSessionService(RefreshSessionRepository repository,
                                 @Value("${app.security.refresh-token-ttl:7d}") Duration ttl,
                                 @Value("${app.security.refresh-token-grace-period:10s}") Duration gracePeriod,
                                 Clock clock) {
        this.repository = repository;
        this.tokenGenerator = new RefreshTokenGenerator(new java.security.SecureRandom());
        this.clock = clock;
        this.ttl = ttl;
        this.gracePeriod = gracePeriod;
    }

    @Transactional
    public IssuedRefreshSession issue(UUID userId) {
        String rawToken = tokenGenerator.generate();
        Instant createdAt = clock.instant();
        UUID sessionId = UUID.randomUUID();
        repository.create(sessionId, userId, hash(rawToken), createdAt, createdAt.plus(ttl));
        return new IssuedRefreshSession(sessionId, userId, repository.findRoles(userId), rawToken, createdAt.plus(ttl));
    }

    @Transactional(noRollbackFor = RefreshSessionReuseException.class)
    public IssuedRefreshSession rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshSessionRepository.RefreshSessionRecord old = repository.findForUpdate(hash(rawToken));
        if (old == null) {
            throw new IllegalArgumentException("Refresh session is invalid");
        }
        if (old.revokedAt() != null) {
            if (old.replacedBySessionId() != null) {
                Duration elapsed = Duration.between(old.revokedAt(), now);
                if (!elapsed.isNegative() && elapsed.compareTo(gracePeriod) <= 0) {
                    throw new IllegalArgumentException("Refresh session is invalid");
                }
                repository.revokeFamily(old.id(), now);
                throw new RefreshSessionReuseException("Refresh session reuse detected");
            }
            throw new IllegalArgumentException("Refresh session is invalid");
        }
        if (!old.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("Refresh session is invalid");
        }
        IssuedRefreshSession replacement = issue(old.userId());
        repository.revoke(old.id(), now, replacement.sessionId());
        return replacement;
    }

    @Transactional
    public void revoke(String rawToken) {
        RefreshSessionRepository.RefreshSessionRecord session = repository.findForUpdate(hash(rawToken));
        if (session != null && session.revokedAt() == null) {
            repository.revoke(session.id(), clock.instant(), null);
        }
    }

    @Transactional
    public int revokeAll(UUID userId) {
        return repository.revokeAll(userId, clock.instant());
    }

    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedRefreshSession(UUID sessionId, UUID userId, java.util.List<String> roles,
                                       String rawToken, Instant expiresAt) {
    }
}
