package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.AdminIdempotencyStore;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.BankingValidationException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-ADM-CON-03: Admin idempotency state machine.
 *
 * <p>Insert-first algorithm (design/02 §6.4):</p>
 * <ol>
 *   <li>{@link #precheck} reads a committed record and replays/conflicts before any challenge is issued.
 *       This makes a retry after a lost response work even if the target's provider version changed.</li>
 *   <li>{@link #claim} inserts {@code RECEIVED} inside the caller business transaction with
 *       {@code ON CONFLICT DO NOTHING}. The unique index is the only arbiter for concurrent requests.</li>
 *   <li>{@link #complete} stores the exact masked response and the approval reference in that same transaction.
 *       A business failure rolls the claim back, so a retry is a clean new attempt and a committed
 *       {@code RECEIVED} row can never exist.</li>
 * </ol>
 *
 * <p>Concurrent same key: the loser blocks on the unique index until the winner commits, then reads the terminal
 * record and replays it (same payload) or receives {@code IDEMPOTENCY_CONFLICT} (different payload). If the winner
 * rolled back, the loser's insert succeeds and it executes the operation instead. A durable {@code RECEIVED} row is
 * only ever produced by a future two-phase flow; it is reported as {@code 409 IDEMPOTENCY_IN_PROGRESS} and is never
 * purged.</p>
 *
 * <p>TTL: 24h from completion, inheriting the canonical {@code IDEMPOTENCY_RECORD} proposal (04 §9.6, BRL-013/OI-001).
 * Expiry alone does not change replay/conflict behavior; only the purge job makes the key reusable.</p>
 */
@Service
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminIdempotencyService {

    /** 07 §5.2 header contract: 1–255 characters, no PII. */
    static final String KEY_PATTERN = "^[A-Za-z0-9._:~-]{1,255}$";
    static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    /** Result of a read-only precheck performed before challenge issuance/provider capture. */
    public sealed interface Precheck {
        record Absent() implements Precheck {
        }

        record Replay(AdminIdempotencyRecord record) implements Precheck {
        }
    }

    /** Result of the transactional insert-first claim. */
    public sealed interface Claim {
        record Claimed(long recordId) implements Claim {
        }

        record Existing(AdminIdempotencyRecord record) implements Claim {
        }
    }

    private final AdminIdempotencyStore store;
    private final Clock clock;
    private final Duration ttl;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    public AdminIdempotencyService(AdminIdempotencyStore store, Clock clock, AppProperties properties,
            MeterRegistry meters, ObjectMapper json) {
        this.store = store;
        this.clock = clock;
        this.ttl = Duration.ofHours(properties.admin().idempotencyTtlHours());
        this.meters = meters;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Precheck precheck(AdminIdempotencyScope scope, String idempotencyKey, String requestHash) {
        String key = requireKey(idempotencyKey);
        requireHash(requestHash);
        return store.find(scope, key)
                .<Precheck>map(record -> new Precheck.Replay(resolve(record, requestHash)))
                .orElseGet(Precheck.Absent::new);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(AdminIdempotencyScope scope, String idempotencyKey, String requestHash) {
        String key = requireKey(idempotencyKey);
        requireHash(requestHash);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        // A conflict can only be reported after the conflicting transaction resolves. Reading once more after
        // ON CONFLICT handles both commit and purge races without ever re-executing business logic.
        for (int attempt = 0; attempt < 2; attempt++) {
            var inserted = store.claim(scope, key, requestHash, now, expiresAt);
            if (inserted.isPresent()) {
                metric("admin.idempotency.claims");
                return new Claim.Claimed(inserted.get().recordId());
            }
            var existing = store.find(scope, key);
            if (existing.isPresent()) {
                return new Claim.Existing(resolve(existing.get(), requestHash));
            }
        }
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> complete(long recordId, int responseStatus, Map<String, Object> responseBody,
            long approvalId) {
        Objects.requireNonNull(responseBody, "responseBody is required");
        if (responseStatus < 100 || responseStatus > 599 || approvalId <= 0) {
            throw new IllegalArgumentException("Invalid idempotency completion");
        }
        // Round-trip once so the first response and every replay use the same JSON-derived value types
        // (for example 0 vs 0L). The stored bytes and the returned map then describe the same response.
        Map<String, Object> normalized = normalize(responseBody);
        Instant completedAt = clock.instant();
        store.complete(recordId, responseStatus, normalized, approvalId, completedAt, completedAt.plus(ttl));
        return normalized;
    }

    @Transactional
    public int purgeExpired(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        int deleted = store.purgeExpired(clock.instant(), batchSize);
        if (deleted > 0) {
            metric("admin.idempotency.purged");
        }
        return deleted;
    }

    public Duration ttl() {
        return ttl;
    }

    /** Validates and returns the key; never logs or echoes the rejected value. */
    public static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches(KEY_PATTERN)) {
            throw new BankingValidationException("Idempotency-Key",
                    "Idempotency-Key là bắt buộc và phải dài 1-255 ký tự hợp lệ.");
        }
        return idempotencyKey;
    }

    private AdminIdempotencyRecord resolve(AdminIdempotencyRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            metric("admin.idempotency.conflict");
            throw new BankingBusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (!record.terminal()) {
            metric("admin.idempotency.in_progress");
            throw new BankingBusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
        metric("admin.idempotency.replay");
        return record;
    }

    private static void requireHash(String requestHash) {
        if (requestHash == null || !requestHash.matches(HASH_PATTERN)) {
            throw new IllegalStateException("Admin idempotency hash must be SHA-256 hex");
        }
    }

    private Map<String, Object> normalize(Map<String, Object> body) {
        try {
            Map<String, Object> normalized = json.readValue(json.writeValueAsString(body),
                    new TypeReference<Map<String, Object>>() {
                    });
            return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        } catch (JsonProcessingException invalid) {
            // Never echo the response body.
            throw new IllegalStateException("Idempotency response is not JSON-serializable");
        }
    }

    private void metric(String name) {
        try {
            meters.counter(name).increment();
        } catch (RuntimeException metricsFailure) {
            // Metrics must never change an idempotency decision.
        }
    }
}
