package com.simulatedbank.banking.limit.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Framework-free aggregate for one version of a Limit configuration
 * ({@code banking.limit_config}, 04 §4.5.1).
 *
 * <p><b>Invariants.</b> Money is VND, positive, fits {@code NUMERIC(19,2)} and is
 * normalized to scale 2; a daily limit is never smaller than the per-transaction limit.
 * A pending row is created for {@code expectedVersion + 1}, where {@code expectedVersion}
 * is the ACTIVE version captured before the Maker step-up challenge ({@code 0} = first
 * version). Applying the change is the only way to reach {@code ACTIVE}; the previous
 * ACTIVE row must be superseded in the same transaction. Verified rows always carry the
 * checker and approval time, rejected rows never do (mirrors the DB checks).</p>
 *
 * <p><b>Concurrency.</b> This type contains no locking. The persistence adapter locks the
 * pending/ACTIVE rows with {@code SELECT ... FOR UPDATE} and the service compares the
 * locked ACTIVE version with the expected version; a mismatch is a
 * {@code ROLE_CONFLICT}, never a lost update. The shared error catalog has no
 * limit-specific version-conflict code, so the documented role/version conflict code is
 * reused (see the port Javadoc).</p>
 */
public final class LimitConfig {

    private static final String CURRENCY_VND = "VND";
    private static final int MONEY_SCALE = 2;
    private static final int MONEY_INTEGER_DIGITS = 17;

    private final long limitConfigId;
    private final long customerId;
    private final Long accountId;
    private final BigDecimal perTransactionLimit;
    private final BigDecimal dailyLimit;
    private final String currency;
    private final Instant effectiveFrom;
    private final LimitConfigStatus status;
    private final int version;
    private final long createdBy;
    private final Long approvedBy;
    private final Long approvalId;

    private LimitConfig(long limitConfigId, long customerId, Long accountId, BigDecimal perTransactionLimit,
            BigDecimal dailyLimit, String currency, Instant effectiveFrom, LimitConfigStatus status, int version,
            long createdBy, Long approvedBy, Long approvalId) {
        this.limitConfigId = limitConfigId;
        this.customerId = customerId;
        this.accountId = accountId;
        this.perTransactionLimit = perTransactionLimit;
        this.dailyLimit = dailyLimit;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
        this.status = status;
        this.version = version;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
        this.approvalId = approvalId;
    }

    /**
     * Creates the PENDING_APPROVAL row of an approved proposal. The persisted identifier is
     * assigned by the database.
     *
     * @throws BankingBusinessException {@code VALIDATION_ERROR} for structural violations
     */
    public static LimitConfig pending(long approvalId, long createdBy, long customerId, Long accountId,
            BigDecimal perTransactionLimit, BigDecimal dailyLimit, String currency, Instant effectiveFrom,
            long expectedVersion) {
        requirePositive(approvalId, "approvalId");
        requirePositive(createdBy, "createdBy");
        requirePositive(customerId, "customerId");
        if (accountId != null) {
            requirePositive(accountId, "accountId");
        }
        int version = pendingVersion(expectedVersion);
        if (effectiveFrom == null) {
            throw invalid();
        }
        BigDecimal perTransaction = money(perTransactionLimit);
        BigDecimal daily = money(dailyLimit);
        requireDailyCoversPerTransaction(perTransaction, daily);
        return new LimitConfig(0L, customerId, accountId, perTransaction,
                daily, currency(currency), effectiveFrom,
                LimitConfigStatus.PENDING_APPROVAL, version, createdBy, null, approvalId);
    }

    /** Rebuilds a persisted row; used by the JDBC adapter only. */
    public static LimitConfig restore(long limitConfigId, long customerId, Long accountId,
            BigDecimal perTransactionLimit, BigDecimal dailyLimit, String currency, Instant effectiveFrom,
            LimitConfigStatus status, int version, long createdBy, Long approvedBy, Long approvalId) {
        if (limitConfigId <= 0 || version <= 0) {
            throw new IllegalStateException("Stored limit configuration has an invalid identifier/version");
        }
        requirePositive(customerId, "customerId");
        if (accountId != null) {
            requirePositive(accountId, "accountId");
        }
        requirePositive(createdBy, "createdBy");
        if (approvalId != null) {
            requirePositive(approvalId, "approvalId");
        }
        if (approvedBy != null) {
            requirePositive(approvedBy, "approvedBy");
        }
        Objects.requireNonNull(status, "status is required");
        boolean decided = status == LimitConfigStatus.ACTIVE || status == LimitConfigStatus.SUPERSEDED;
        if (decided != (approvedBy != null)) {
            throw new IllegalStateException("Stored limit configuration has inconsistent decision fields");
        }
        if (decided && approvedBy == createdBy) {
            throw new IllegalStateException("Stored limit configuration violates maker/checker separation");
        }
        BigDecimal perTransaction = money(perTransactionLimit);
        BigDecimal daily = money(dailyLimit);
        requireDailyCoversPerTransaction(perTransaction, daily);
        return new LimitConfig(limitConfigId, customerId, accountId, perTransaction,
                daily, currency(currency),
                Objects.requireNonNull(effectiveFrom, "effectiveFrom is required"), status, version, createdBy,
                approvedBy, approvalId);
    }

    /**
     * Validates the expected-version shape and returns the version of a pending row created
     * from it. {@code expectedVersion == 0} means the scope has no ACTIVE configuration yet.
     *
     * @throws BankingBusinessException {@code VALIDATION_ERROR} when negative/not representable as INT
     */
    public static int pendingVersion(long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion > Integer.MAX_VALUE - 2L) {
            throw invalid();
        }
        return (int) expectedVersion + 1;
    }

    /** {@code PENDING_APPROVAL -> ACTIVE}; records the checker and approval time. */
    public LimitConfig activate(long checkerUserId, Instant approvedAt) {
        status.requireTransitionTo(LimitConfigStatus.ACTIVE);
        requirePositive(checkerUserId, "checkerUserId");
        if (checkerUserId == createdBy) {
            // Maker/checker separation is a hard rule (04 §4.5.1); the DB check is the last line of defence.
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
        Objects.requireNonNull(approvedAt, "approvedAt is required");
        Instant cutover = effectiveFrom.isAfter(approvedAt) ? effectiveFrom : approvedAt;
        return new LimitConfig(limitConfigId, customerId, accountId, perTransactionLimit, dailyLimit, currency,
                cutover, LimitConfigStatus.ACTIVE, version, createdBy, checkerUserId, approvalId);
    }

    /** {@code ACTIVE -> SUPERSEDED}; keeps the checker/time that made the version effective. */
    public LimitConfig supersede() {
        status.requireTransitionTo(LimitConfigStatus.SUPERSEDED);
        return new LimitConfig(limitConfigId, customerId, accountId, perTransactionLimit, dailyLimit, currency,
                effectiveFrom, LimitConfigStatus.SUPERSEDED, version, createdBy, approvedBy, approvalId);
    }

    /** {@code PENDING_APPROVAL -> REJECTED}; decision actor/time stay in the Admin approval row. */
    public LimitConfig reject() {
        status.requireTransitionTo(LimitConfigStatus.REJECTED);
        return new LimitConfig(limitConfigId, customerId, accountId, perTransactionLimit, dailyLimit, currency,
                effectiveFrom, LimitConfigStatus.REJECTED, version, createdBy, null, approvalId);
    }

    /**
     * Ensures this pending row is exactly the successor of {@code expectedVersion}.
     *
     * @throws BankingBusinessException {@code ROLE_CONFLICT} on any mismatch
     */
    public void requirePendingVersion(long expectedVersion) {
        if (version != pendingVersion(expectedVersion)) {
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
    }

    /** True when this is the ACTIVE row the caller captured as {@code expectedVersion}. */
    public boolean matchesActiveVersion(long expectedVersion) {
        return status == LimitConfigStatus.ACTIVE && expectedVersion > 0 && version == expectedVersion;
    }

    public long limitConfigId() {
        return limitConfigId;
    }

    public long customerId() {
        return customerId;
    }

    public Long accountId() {
        return accountId;
    }

    public BigDecimal perTransactionLimit() {
        return perTransactionLimit;
    }

    public BigDecimal dailyLimit() {
        return dailyLimit;
    }

    public String currency() {
        return currency;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public LimitConfigStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    public long createdBy() {
        return createdBy;
    }

    public Long approvedBy() {
        return approvedBy;
    }

    public Long approvalId() {
        return approvalId;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw invalid();
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notRepresentable) {
            throw invalid();
        }
        if (normalized.precision() - normalized.scale() > MONEY_INTEGER_DIGITS) {
            throw invalid();
        }
        return normalized;
    }

    private static void requireDailyCoversPerTransaction(BigDecimal perTransaction, BigDecimal daily) {
        if (daily.compareTo(perTransaction) < 0) {
            throw invalid();
        }
    }

    private static String currency(String value) {
        if (!CURRENCY_VND.equals(value)) {
            throw invalid();
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, name + " must be positive");
        }
    }

    private static BankingBusinessException invalid() {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
