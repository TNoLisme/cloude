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

`CreateTransferService.create` owns `@Transactional` boundary:

1. Authenticate actor; resolve source ownership through account module; require Customer role, configured/unlocked PIN, verify PIN via identity API. Increment/lock PIN failure policy atomically on invalid PIN; no money mutation.
2. Validate DTO, source != destination, VND amount and Idempotency-Key (16..128 per OpenAPI).
3. Canonicalize hash payload using stable field order and normalized exact values (`sourceAccountId`, `destinationAccountId`, `amount`, `currency`, `memo` including null semantics). Scope key `(actorId, operation=createTransfer)`.
4. Claim/replay idempotency record. Same key/hash returns existing current response/resource; different hash returns 409 `IDEMPOTENCY_KEY_REUSED`. Concurrent claim is protected by unique index; retry outside rollback-only transaction as Phase 04 design.
5. Lock both accounts in `ORDER BY id ASC FOR UPDATE` using PostgreSQL native query; validate existence, customer ownership of source, ACTIVE states, same currency, amount, sufficient balance under locks.
6. Insert transfer as COMPLETED with timestamps; debit source and credit destination using SQL numeric arithmetic; append audit fact; persist original status/body/resource on idempotency row.
7. Commit; only then return 201 Transfer. Risk is triggered only after commit (best-effort Phase 06).

Any failure before commit rolls back debit, credit, transfer, idempotency outcome and audit facts. No network calls, OTP dispatch, risk execution or broker calls inside money transaction.

## Large transfer create (> 5M)

1. Authenticate actor, validate source ownership and PIN exactly as small transfer path.
2. Claim idempotency key. Create transfer `AWAITING_OTP`, `expires_at=now+120s`, create OTP challenge bound to source customer's registered phone and purpose `TRANSFER_STEP_UP` using local mailbox adapter in local/demo.
3. Commit challenge + transfer + original idempotency response atomically. Do not reserve, debit or credit money.
4. Dispatch to OtpSender outside the money transaction. For local mailbox, write in-memory entry only. If dispatch fails, do not represent challenge as sent; mark challenge/transfer safely failed only through a documented compensating state transition, or return safe retriable service error. No Outbox is allowed. This send-vs-commit failure window is inherent; document and test best-effort semantics. If exact API cannot truthfully report dispatch, use adapter call before creating transaction only if OTP invalidation on rollback is safe; design must not leak a live OTP for nonexistent challenge.
5. Replay same key/hash returns current challenge with remaining TTL while awaiting; no new OTP is issued. If terminal/completed, return current Transfer representation as OpenAPI `oneOf` allows. Set `Idempotency-Replayed: true`.

## Confirm OTP

`ConfirmTransferOtpService.confirm` has two carefully bounded transactional operations to protect OTP attempt state and money atomicity:

1. Load transfer and verify source actor ownership; missing/non-owner response follows OpenAPI concealment rules.
2. Lock challenge row; if terminal/expired, expire transfer state via transaction and return documented 409; if invalid code, increment attempts, on fifth mark challenge invalid and transfer FAILED with failureCode, commit those state changes, then return 400 `OTP_INVALID` after transaction. Avoid throwing inside transaction before attempt counter commits.
3. For valid OTP, consume challenge tentatively in current transaction; lock account rows sorted by PostgreSQL `id ASC FOR UPDATE`.
4. Revalidate transfer remains AWAITING_OTP and not expired; both accounts ACTIVE, same VND currency, source ownership, sufficient available balance. On failure, mark FAILED/failure code, consume OTP, commit no balance changes; return contract-defined status/error. The exact mapping must follow current OpenAPI and team contract; do not add a status.
5. On success debit/credit, mark transfer COMPLETED/completed_at, append audit fact, commit atomically. Risk event publishes AFTER_COMMIT. Return 201.
6. Repeated confirm after COMPLETED returns original transfer 200 with `Idempotency-Replayed: true`; never consume another OTP or move funds.

Implementation must separate a transaction result from HTTP exception mapping so attempts/failure status commit before returning a 400/409 where required.

## Risk event and atomicity

Publish an internal application event only after transfer commit, using `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. Do not invoke listener synchronously before commit. It is best-effort; exception cannot roll back transfer. Phase 06 specifies logging and idempotent risk persistence. No Outbox/broker or network calls inside transaction.

## History/status queries

- `GET /transfers` filters `status`, `from`, `to`, `limit`, `cursor` exactly as OpenAPI; from inclusive and to exclusive per contract docs; sorted `(created_at DESC, id DESC)`.
- Cursor payload encodes timestamp + transfer UUID as UTF-8, Base64 URL-safe without padding. Validate length, timestamp parse and UUID; malformed cursor maps `400 INVALID_CURSOR` only if code exists in OpenAPI, otherwise `VALIDATION_ERROR`.
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
- OTP success, wrong OTP attempts 1–4, fifth → FAILED and 400 OTP_INVALID with persisted count, expiry → EXPIRED/409, revalidation failure no money change.
- Duplicate create same key/same payload concurrent: one logical transfer; changed payload conflict.
- Duplicate confirm concurrent: one debit/credit; replay 200.
- PostgreSQL test proves ORDER BY id ASC locking, opposite-direction transfer no persistent deadlock, simultaneous debits do not overdraw, account-block race.
- Fault injection between debit/credit proves rollback of all same-transaction rows.
- Timeout after commit + retry returns original logical result. App restart preserves idempotency result.
- Masking, participant access, cursor tie-breaks and malformed cursor tests.
- Testcontainers PostgreSQL only; no shared DB.

## Implementation loop

Inspect actual status/error schemas and account public API. User-approved decisions include lock ordering, threshold, OTP policy, atomicity, idempotency and risk best-effort. Ask only if OpenAPI contradicts required failure mapping or event delivery semantics. After phase approval, implement recipient resolve, immediate transfer, challenge/replay, confirm state machine, history, then concurrency/failure suite; review complete diff and update evidence. Stop before Phase 06 pending approval.

**Verification record:** pending implementation approval.
