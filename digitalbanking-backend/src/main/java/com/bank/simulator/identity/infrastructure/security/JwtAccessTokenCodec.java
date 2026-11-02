package com.bank.simulator.identity.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAccessTokenCodec {

    private final SecretKey signingKey;
    private final Clock clock;
    private final long ttlSeconds;

    public JwtAccessTokenCodec(@Value("${app.security.jwt-secret:}") String secret,
                               @Value("${app.security.access-token-ttl:900s}") java.time.Duration ttl,
                               Clock clock) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.security.jwt-secret must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.clock = clock;
        this.ttlSeconds = ttl.toSeconds();
        if (ttlSeconds < 1) {
            throw new IllegalStateException("app.security.access-token-ttl must be positive");
        }
    }

    public String issue(UUID userId, List<String> roles) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.copyOf(roles))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
