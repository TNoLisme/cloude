package com.simulatedbank.banking.audit.domain;

import com.simulatedbank.banking.common.crypto.HmacService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Hash-chain constants and pure formatting rules shared by validation and persistence (design/01 §7.2–§7.5).
 *
 * <p>The chain is global and ordered by {@code audit_id}; every record hash covers the full canonical payload of
 * its {@code hash_version} plus the previous record hash, so removing, reordering or rewriting a record breaks
 * verification. {@code hash_version} lives inside the canonical payload and is never concatenated a second time.
 * This type has no framework dependency so the formula can be unit-tested against fixed vectors.</p>
 */
public final class AuditChain {

    /** Prefix of the first record in a chain (design/01 §7.3). */
    public static final String GENESIS_PREV_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    /** Canonical payload version written by this provider; the field set of a version is fixed. */
    public static final int HASH_VERSION = 1;

    /**
     * One fixed 64-bit key for the global chain. The value is the ASCII bytes of {@code AUDITCHN}; it is part of
     * the on-disk protocol and must never be changed without a migration/verification plan (design/01 §7.5).
     */
    public static final long CHAIN_LOCK_KEY = 4_707_744_067_222_718_542L;

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final int MAX_AMOUNT_INTEGER_DIGITS = 17;

    private AuditChain() {
    }

    /** Canonical timestamps are UTC with exactly six fractional digits (microsecond precision). */
    public static Instant normalizeOccurredAt(Instant occurredAt) {
        return Objects.requireNonNull(occurredAt, "occurredAt is required").truncatedTo(ChronoUnit.MICROS);
    }

    /** Formats an already normalised instant as {@code 2026-11-03T10:15:31.123456Z}. */
    public static String formatOccurredAt(Instant occurredAt) {
        return OCCURRED_AT.format(normalizeOccurredAt(occurredAt));
    }

    /**
     * Canonical money string with exactly two decimals. Extra fractional digits are rejected instead of silently
     * rounded so the DB value and the hashed value always agree.
     */
    public static String formatAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.precision() - amount.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount is out of range");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * {@code lowercase_hex(SHA-256(UTF8(canonical_json) || "|" || prev_hash))} (design/01 §7.3). String
     * concatenation is byte-identical here because the canonical payload is UTF-8 text and {@code "|"} is ASCII.
     */
    public static String recordHash(String canonicalJson, String prevHash) {
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        Objects.requireNonNull(prevHash, "prevHash is required");
        return HmacService.sha256Hex(canonicalJson + "|" + prevHash);
    }
}
