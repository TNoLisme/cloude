# Phase 05 — Internal Transfers

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Phases 01–04; canonical transfer/idempotency/account schema in [`00-roadmap.md`](./00-roadmap.md).  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## Goal

Deliver financially correct internal VND transfers, with recipient privacy, PIN authorization, OTP step-up above 5,000,000 VND, idempotency, concurrency safety, complete transfer status/retry semantics and atomic audit facts.

## Classes and module interfaces

```text
transfer/api/TransferModuleApi.java
transfer/application/ResolveRecipientService.java
transfer/application/CreateTransferService.java
transfer/application/ConfirmTransferOtpService.java
transfer/application/TransferQueryService.java
transfer/application/TransferExpirationPolicy.java
transfer/domain/Transfer.java
transfer/domain/TransferStatus.java
transfer/domain/TransferPolicy.java
transfer/domain/TransferLockOrder.java
transfer/infrastructure/persistence/TransferEntity.java
transfer/infrastructure/persistence/TransferRepository.java
transfer/infrastructure/persistence/TransferLockRepository.java
transfer/infrastructure/persistence/TransferIdempotencyService.java
transfer/web/RecipientController.java
transfer/web/TransferController.java
account/api/AccountModuleApi.java
identity/api/IdentityModuleApi.java
audit/api/AuditModuleApi.java
```

Conceptual module API:

```java
public interface AccountModuleApi {
    LockedAccountPair lockAccountsAscending(UUID accountA, UUID accountB);
    AccountView findByAccountNumber(String fullAccountNumber);
    void debit(UUID accountId, BigDecimal amount);
    void credit(UUID accountId, BigDecimal amount);
    AccountTransferSnapshot validateTransferEligibility(UUID sourceId, UUID destinationId);
}
public interface TransferModuleApi {
    TransferView createTransfer(CreateTransferCommand command);
    TransferView confirmOtp(ConfirmOtpCommand command);
    TransferPageView listTransfers(TransferQuery query);
    TransferView getTransfer(UUID actorId, UUID transferId);
}
```

`LockedAccountPair` must expose immutable values and only be used within an active transaction. Account module owns SQL and repositories. Transfer service never injects `AccountRepository`.

## Money parsing

OpenAPI accepts amount as canonical integer string. Parse with `new BigDecimal(value)` only after regex/contract validation; require `scale() == 0`, `signum() > 0`, range [2000, 10000000], `toPlainString()` equals input canonical form. Reject fractions, leading zero, plus/minus sign, whitespace and overflow before DB mutation. Use `BigDecimal` and SQL `NUMERIC(19,0)` only. No float/double or rounding.

## Account row locking

Use one database query per participant, in SQL-determined order:

```sql
SELECT id, customer_id, balance, currency, status
FROM accounts
WHERE id = :first_id
FOR UPDATE;
SELECT id, customer_id, balance, currency, status
FROM accounts
WHERE id = :second_id
FOR UPDATE;
```

Before executing, resolve which UUID is first using PostgreSQL ordering, not Java UUID natural comparison. Preferred repository method uses a single query:

```sql
SELECT id, customer_id, balance, currency, status
FROM accounts
WHERE id IN (:account_a, :account_b)
ORDER BY id ASC
FOR UPDATE;
```

Verify PostgreSQL execution plan/locking semantics and ensure both rows returned in stable order. If JPA `@Lock(PESSIMISTIC_WRITE)` cannot guarantee emitted `ORDER BY id ASC FOR UPDATE`, use native repository query. Require exactly two distinct rows. Every transfer path uses same ordering. One-account operations lock only one row. Lock timeout/deadlock errors map to safe retriable `503 SERVICE_UNAVAILABLE` or exact documented contract; never claim success.

## Recipient resolution

`POST /recipients/resolve` is authenticated CUSTOMER and rate-limited by customer UUID 30/min. Resolve full account number exact; only ACTIVE eligible VND account returns account ID, masked number, limited recipient display name and currency. Nonexistent/ineligible results must map to same not-available code/status according to OpenAPI (404 recipient unavailable). Do not return phone/email/full account number. Account and customer lookup through module APIs.

## Small transfer create (amount <= 5M)

`CreateTransferService.create` orchestrates PIN verification before starting the money transaction. Call a separate Spring-managed PIN service with `REQUIRES_NEW`: lock PIN row, check lock expiry, verify PIN and update attempt/lock state atomically, commit and return a typed result. Map an invalid/locked result to the existing HTTP error only after this call returns. No outer money transaction or PIN row lock may be held during this call.

1. Authenticate actor; resolve source ownership through account module; require Customer role and configured PIN; verify PIN through the independent transaction above. A later money rollback must not undo PIN attempt state.
2. Validate DTO, source != destination, VND amount and Idempotency-Key (16..128 per OpenAPI).
3. Canonicalize hash payload using stable field order and normalized exact values (`sourceAccountId`, `destinationAccountId`, `amount`, `currency`, `memo` including null semantics). Scope key `(actorId, operation=createTransfer)`.
4. Start the money transaction and claim/replay idempotency record. Same key/hash returns existing current response/resource; different hash returns 409 `IDEMPOTENCY_KEY_REUSED`. Concurrent claim is protected by unique index; retry outside rollback-only transaction as Phase 04 design.
5. Lock both accounts in `ORDER BY id ASC FOR UPDATE` using PostgreSQL native query; validate existence, customer ownership of source, ACTIVE states, same currency, amount, sufficient balance under locks.
6. Insert transfer as COMPLETED with timestamps; debit source and credit destination using SQL numeric arithmetic; append audit fact; persist original status/body/resource on idempotency row.
7. Commit; only then return 201 Transfer. Risk is triggered only after commit (best-effort Phase 06).

Any failure before commit rolls back debit, credit, transfer, idempotency outcome and audit facts. No network calls, OTP dispatch, risk execution or broker calls inside money transaction.

## Large transfer create (> 5M)

1. Authenticate actor, validate source ownership and PIN exactly as small transfer path.
2. Claim idempotency key. Create transfer `AWAITING_OTP`, `expires_at=now+120s`, create OTP challenge bound to source customer's registered phone and purpose `TRANSFER_STEP_UP` using local mailbox adapter in local/demo.
3. Commit challenge + transfer + original idempotency response atomically. Do not reserve, debit or credit money.
4. Dispatch to OtpSender only after commit. For local mailbox, write in-memory entry only. On dispatch failure or timeout, use a new compensating transaction: lock the transfer and its challenge, change only AWAITING_OTP to FAILED with failureCode OTP_DISPATCH_FAILED and invalidate the challenge atomically. Never overwrite COMPLETED, EXPIRED or FAILED. Return 503 SERVICE_UNAVAILABLE for the initial dispatch error; this does not prove money was rolled back if a concurrent confirm already completed. Timeout may mean a message was delivered; a late OTP is unusable after compensation. No Outbox and no dispatch before commit. Same-key replay returns the current Transfer (normally FAILED), never a new OTP.
5. Replay same key/hash returns current challenge with remaining TTL while awaiting; no new OTP is issued. If terminal/completed, return current Transfer representation as OpenAPI `oneOf` allows. Set `Idempotency-Replayed: true`.

A new intentional transfer after FAILED requires a new key. A process crash after commit but before dispatch/compensation, or failed compensation, can leave AWAITING_OTP until expiry. Confirm must enforce expires_at; reads must reflect EXPIRED under the existing expiry policy. This is an accepted best-effort limitation, not a delivery guarantee.

## Confirm OTP

Each `ConfirmTransferOtpService.confirm` call uses one transaction and returns a typed outcome. The HTTP layer maps that outcome only after commit. Wrong OTP does not open a nested REQUIRES_NEW transaction.

1. Lock transfer and verify source actor ownership; missing/non-owner response follows OpenAPI concealment rules. If already COMPLETED, return the replay outcome before checking challenge validity. Confirm, expiry and dispatch compensation must use the same lock order: transfer, its challenge, then accounts sorted by PostgreSQL ID when needed.
2. Lock the exact challenge referenced by transfer. Handle terminal/expired state before verification. On wrong OTP, increment attempts; on the fifth, invalidate challenge and mark transfer FAILED in this same transaction. Commit the outcome, then return 400 `OTP_INVALID` for attempts 1–4 or 409 `STATE_CONFLICT` for the fifth. Later confirms on FAILED also return 409 `STATE_CONFLICT`. Do not throw an HTTP exception inside the transaction.
3. For valid OTP, consume challenge tentatively in current transaction; lock account rows sorted by PostgreSQL `id ASC FOR UPDATE`.
4. Revalidate transfer remains AWAITING_OTP and not expired; both accounts ACTIVE, same VND currency, source ownership, sufficient available balance. On failure, mark FAILED/failure code, consume OTP, commit no balance changes; return contract-defined status/error. The exact mapping must follow current OpenAPI and team contract; do not add a status.
5. On success consume OTP, debit/credit, mark transfer COMPLETED/completed_at and append audit fact in this same transaction. Publish the application event while the transaction is active; its listener runs AFTER_COMMIT. Commit before returning 201. Technical failure rolls back OTP consumption and all money/state/audit changes together.
6. Repeated confirm after COMPLETED returns original transfer 200 with `Idempotency-Replayed: true`; never consume another OTP or move funds.

Implementation must separate a transaction result from HTTP exception mapping so attempts/failure status commit before returning a 400/409 where required.

## Risk event and atomicity

Publish the internal application event inside the active successful transfer transaction. Register its listener with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` so rollback never triggers evaluation. The listener invokes a separate Spring-managed RiskEvaluationService using REQUIRES_NEW; catch failures outside that service call, including commit failures. AFTER_COMMIT does not itself make execution asynchronous; measure its contribution to response latency. Phase 06 specifies best-effort logging and idempotent persistence. No Outbox/broker or network calls inside the money transaction.

## History/status queries

- `GET /transfers` filters `status`, `from`, `to`, `limit`, `cursor` exactly as OpenAPI; from inclusive and to exclusive per contract docs; sorted `(created_at DESC, id DESC)`.
- Serialize CursorPosition (version, timestamp, id) to JSON UTF-8, then Base64 URL-safe without padding, as specified in Phase 06. Decode JSON without delimiter splitting; validate length, version, timestamp and UUID. Malformed cursor maps to the existing validation error. Always reapply ownership and query filters.
- SQL next page predicate:

```sql
WHERE (created_at < :cursor_time)
   OR (created_at = :cursor_time AND id < :cursor_id)
ORDER BY created_at DESC, id DESC
LIMIT :limit_plus_one
```

Use `limit+1` to determine `nextCursor`; encode last returned record only when another row exists. Query should use explicit source/destination participant visibility and bound parameters. Status/date filters are parameterized.
- Source owner sees all statuses; destination owner sees only COMPLETED. Detail endpoint returns 404 `TRANSFER_NOT_FOUND` for destination requesting non-completed transfer. Other role visibility exactly follows OpenAPI security/decision baseline; do not infer from operator UI.
- `TransferListItem` exposes direction, masked counterparty, limited display name, amount/currency/times/memo only.

## Acceptance and tests

- Amount canonical/bounds, wrong currency, self transfer, unauthorized source, ineligible source/destination, insufficient balance.
- `<=5M`: one atomic commit and 201, exact debit=credit, audit+idempotency stored.
- `>5M`: 200 AWAITING_OTP, no balance change/reserve, 120s expiry; replay does not resend OTP.
- OTP success, wrong OTP attempts 1–4 → 400 OTP_INVALID, fifth → FAILED and 409 STATE_CONFLICT with persisted count, expiry → EXPIRED/409, revalidation failure no money change.
- Duplicate create same key/same payload concurrent: one logical transfer; changed payload conflict.
- Duplicate confirm concurrent: one debit/credit; replay 200.
- Wrong PIN attempts survive the HTTP error and later money failure; concurrent attempts preserve lockout with no outer transaction holding the PIN lock.
- Wrong OTP attempts persist after HTTP error; fifth attempt commits invalidation and FAILED together. Fault injection after OTP consumption rolls it back together with debit/credit and audit. Already-completed replay accepts no new OTP consumption.
- Dispatch failure/timeout compensates only AWAITING_OTP and invalidates its challenge; confirm/expiry races never overwrite terminal states. Crash before dispatch or failed compensation leaves a pending transfer that expires; same-key replay never resends.
- PostgreSQL test proves ORDER BY id ASC locking, opposite-direction transfer no persistent deadlock, simultaneous debits do not overdraw, account-block race.
- Fault injection between debit/credit proves rollback of all same-transaction rows.
- Timeout after commit + retry returns original logical result. App restart preserves idempotency result.
- Masking, participant access, cursor tie-breaks and malformed cursor tests.
- Testcontainers PostgreSQL only; no shared DB.

## Implementation loop

Inspect actual status/error schemas and account public API. User-approved decisions include lock ordering, threshold, OTP policy, atomicity, idempotency and risk best-effort. Ask only if OpenAPI contradicts required failure mapping or event delivery semantics. After phase approval, implement recipient resolve, immediate transfer, challenge/replay, confirm state machine, history, then concurrency/failure suite; review complete diff and update evidence. Stop before Phase 06 pending approval.

**Verification record:** pending implementation approval.
