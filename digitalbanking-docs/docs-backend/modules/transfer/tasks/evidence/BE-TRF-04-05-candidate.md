# BE-TRF-04/05 — Candidate implementation evidence

> 16/11/2026. Partial implementation inventory; not task completion, provider integration, end-to-end evidence or release approval. Working tree based on `84a4d2e`; candidate source and tests are uncommitted and may change. Reviewer: pending.

## Scope present in source

- `TransferCreateInput` is a candidate input parser, not a registered HTTP endpoint. Eight passing tests cover request shape, UTF-8, byte-size bounds, idempotency header and field validation. It does not enforce the no-PII policy for descriptions or keys; authentication, authorization, HTTP security, Problem mapping and runtime wiring remain outstanding.
- `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy` and `IdempotencyRetention` provide isolated canonical hash, idempotency classification, amount trigger and retention calculations. They do not provide authorization, locks, reservations, database lookup, HTTP replay or purge.
- `TransferPreflightCandidate` is an unregistered orchestration candidate. Its 11 fake tests cover primary lookup before preflight, reread on rejection, winner/conflict/replay handling, sequencing and binding. The candidate hashes an immutable payload and checks the T1 actor, customer, key, hash, source, resolved destination, amount/currency and description against it. Account-number resolution still depends on the trusted Account port; the caller must authenticate and authorize, and real provider, HTTP boundary and security/contract tests remain necessary before activation.
- `TransferIntentCandidate` is an unregistered T1 candidate using internal ports and a transaction template. Its 10 fake tests cover guard and key recheck, challenge, simulated rollback and reading the winning record after rollback. They do not establish real database transaction semantics or provider behavior.
- `TransferSettlementCandidate` is an unregistered T2 candidate using internal ports for guard/fence, account/usage locks, fraud, money/ledger, reservation, terminal/outbox and audit ordering. It compares both epoch and token with the locked fence. Its 19 fake tests include the recovery entry point's `MANDATORY` transaction and rollback-only propagation. Five additional PostgreSQL tests use test-only synthetic provider implementations and tables: success, injected failure rollback, insufficient-balance outcome, stale token/two concurrent attempts, and recovery rollback-only propagation when an inner failure is caught. They establish behavior only for that fixture, not durable production fencing, provider transactions or full recovery.
- The T2 candidate propagates technical fraud failures and credit errors; it does not implement the specified fraud fail-open savepoint, credit savepoint recovery, Tfail, retries or reconciliation. Recovery orchestration and ambiguous-commit resolution remain incomplete. No candidate is production-wired; real provider adapters, authorization, PII/security controls and a complete money path remain outstanding. Internal ports are candidate seams, not implemented or approved provider-owned APIs. See [BE-TRF-02 candidate evidence](BE-TRF-02-candidate-verification.md) for the separate migration/fixture scope; synthetic provider tables are not provider migrations.

## Verification status

Runner: Codex; Java 21 (Temurin `21.0.1`), PostgreSQL `16-alpine` via Testcontainers, local development, synthetic data. From `digitalbanking-backend`: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew clean check --offline --console=plain`. Result: `BUILD SUCCESSFUL`; XML reports **298 backend tests, 0 failures, 0 errors and 0 skipped**, including **95 Transfer tests**: 70 unit/fake tests and 25 PostgreSQL tests using synthetic fixtures. Of the PostgreSQL tests, 5 are the T2 settlement candidate below; 12 migration and 8 JPA tests are BE-TRF-02 scope ([candidate evidence](BE-TRF-02-candidate-verification.md)), and 2 ArchUnit boundary tests are also BE-TRF-02 scope. This is a snapshot of the run; subsequent source or test changes require a fresh run and updated counts. It does not constitute provider integration, end-to-end, D1–D5 or release evidence.

| Scope | Evidence / status |
|---|---|
| Input parser tests | 8 passed for shape, UTF-8, size and headers; no endpoint wiring or PII policy. |
| Primitive and domain unit tests | 20 passed: 15 for hash, classification, amount triggers and retention; 5 domain state/value tests. |
| Preflight fake tests | 11 passed; real Account resolution, authorization and HTTP boundary remain integration work. |
| T1 fake tests | 10 passed; rollback behavior is simulated. |
| T2 fake tests | 19 passed, including recovery `MANDATORY`; provider behavior is simulated. |
| T2 PostgreSQL tests | 5 passed against synthetic provider tables/implementations, including recovery rollback-only; no real provider is exercised. |
| Migration PostgreSQL tests | 12 passed against synthetic provider fixtures (BE-TRF-02 scope); production provider migrations remain unverified. |
| JPA and boundary tests | 8 JPA + 2 ArchUnit boundary passed (BE-TRF-02 scope, synthetic fixtures); see [BE-TRF-02 candidate evidence](BE-TRF-02-candidate-verification.md). |
| Real providers and complete money path | Not verified; post-merge integration evidence and security review required. |

## Post-merge checks

Run the shared provider/consumer contract vectors and PostgreSQL tests with real migrations and independent connections. Verify T1 guard/key races, challenge binding and rollback; T2 lock order, conditional debit, balanced ledger, limit/usage, reservation and outbox atomicity; audit C1/C2 failures and fraud savepoint behavior; terminal replay, TTL and purge races. Exercise stale fencing, simultaneous workers, crash windows and ambiguous commit using primary-read/reconciliation evidence. Verify authorization, masking and production wiring, and run D1–D5 and the release gates in the test plan. Record revision, command, environment, result and artifacts without real customer data. Any missing provider, test or review remains blocked; fake results do not prove these properties.
