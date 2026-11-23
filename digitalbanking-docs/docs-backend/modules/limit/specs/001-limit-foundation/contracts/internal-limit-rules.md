# Internal E1 Contract

No REST endpoints. These internal snapshots are not the later HTTP LimitsResponse.

## LimitRules

- resolve(long customerId, long sourceAccountId, Instant asOf) -> ApplicableLimits: immutable list of ACTIVE effective configs for this pair, customer first/account second, no fallback or unrelated data. Start inclusive/end exclusive, including nanosecond asOf inputs against PostgreSQL microsecond boundaries. Preserve the original asOf in the result.
- checkPerTransaction(long customerId, long sourceAccountId, BigDecimal amount, Instant asOf) -> CheckOutcome.PASS when all present ceilings allow; NOT_CONFIGURED when none. Exceeding either throws BankingBusinessException(LIMIT_EXCEEDED). Invalid money throws VALIDATION_ERROR even if no config.
- ApplicableLimits exposes customerId/sourceAccountId/asOf/items; effectivePerTransactionLimit() is Optional minimum ceiling (empty if none).
- Reads do not create/lock/increment usage or mutate config. E2 must re-read under T2 locks; PASS only covers per-transaction check at this instant.
- Caller supplies authenticated/authorized pair; filtering cannot certify account ownership before owner tables exist.

## LimitJdbcRepository

Insert configuration/usage snapshots under MANDATORY caller transaction, returning stored identity/timestamps. Reject supplied IDs (append new versions only) and invalid decimal precision/range before JDBC. All source 04 §4.5 fields round-trip. Read configuration by ID, usage by scope/date, and active configs for pair/instant. No update/delete or activation methods.
Configuration state is a four-value enum. SQL enforces nonmonetary constraints. DB defaults apply to absent created/updated timestamp inputs and direct seed/migration omitted defaults.
