# Internal contract — LimitEnforcement

Caller must authenticate customer/source ownership and acquire transfer + account locks in canonical order before calling. READ COMMITTED caller transaction using the backend JpaTransactionManager is mandatory; provider verifies the bound physical JPA transaction is active, since Spring metadata alone remains present during afterCommit. Provider never creates/commits an independent money transaction.

`Check lockAndCheck(long customerId, long sourceAccountId, BigDecimal amount)`

- Captures database transaction start instant and date using app.timezone; customer mutex before configured account counter.
- Returns PASS with opaque handle only when every configured per-transaction/daily limit permits amount (equality allowed). Returns NOT_CONFIGURED when both absent; zero mutex row may exist but amount/count remain zero and completion forbidden.
- Exposes outcome, asOf, usageDate and locked usage snapshots for caller; snapshots are not mutable authorizations.
- Invalid amount → VALIDATION_ERROR; exceeded → LIMIT_EXCEEDED. These exceptions do not poison caller transaction: catch and persist business FAILED without increment.
- SQL/technical errors propagate and roll back caller. No config default/fallback, no error mapping for absent transfer config.

`void recordCompleted(Check check)`

- Call only after money/ledger succeeded, before terminal/audit commit. Provider only accounts usage; it does not set transfer state.
- Same provider, exact same active transaction, PASS, not already recorded. Handle expires at beforeCompletion, before commit/rollback and afterCommit callbacks. Invalid handle → runtime system error, caller rollback-only.
- Increments captured customer and configured account once using original date/amount, under locks held from check. If earlier checks in same T2 exhausted capacity, abort transaction rather than commit excessive usage.
- Caller/audit failure after increment rolls back all usage. Repeat call rejected; this guard is not transfer idempotency across transactions. Retry/recovery must first use transfer terminal-state guard; LIM-05 deferred.

LIM-05 future validation: actual transfer rejection leaves no money/ledger/usage; completion at threshold; cross-account contention; terminal replay and recovery avoid invoking increment twice; audit failure after increment rolls back all T2. Deferred D4: newly enabled account scope mid-day requires agreed backfill/reset policy in E4, not inferred here.
