# Phase 04 — Account and Operator Operations

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Phases 01–03; canonical DDL V2/V3 is in [`00-roadmap.md`](./00-roadmap.md).  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## Goal and operation map

Implement authenticated Customer account reads and Operator account operations:

- `GET /accounts` → caller's account page.
- `GET /accounts/{accountId}` → exact contract access semantics; OpenAPI roles inherited from bearer security and responses 200/401/403/404.
- `GET /operator/customers?phone=...` or `?email=...` → exact-match customer + accounts, OPERATOR/ADMIN, 200/400/401/403/404/429.
- `POST /operator/accounts/{accountId}/seed-balance` → OPERATOR/ADMIN; required Idempotency-Key; 201 new, 200 replay, documented Problem errors.
- `POST /operator/accounts/{accountId}/block` and `/unblock` → OPERATOR/ADMIN; mandatory reason; 200 idempotent state; 409 closed/ineligible.

No endpoint/request/response/status changes. `GET /accounts/{accountId}` Operator access remains only if explicit route authorization policy from OpenAPI/team contract permits it; if contradictory docs cannot resolve, stop before implementation and ask.

## Java design

```text
account/api/AccountModuleApi.java
account/application/AccountQueryService.java
account/application/SeedBalanceService.java
account/application/ChangeAccountStatusService.java
account/domain/AccountStatus.java
account/domain/MoneyAmount.java                  # value wrapper over BigDecimal scale 0, if useful
account/domain/SeedPolicy.java
account/infrastructure/persistence/AccountEntity.java
account/infrastructure/persistence/AccountRepository.java
account/infrastructure/persistence/SeedRecordEntity.java
account/infrastructure/persistence/SeedRecordRepository.java
account/infrastructure/persistence/AccountLockRepository.java
account/web/AccountController.java
account/web/OperatorAccountController.java
account/web/OperatorCustomerLookupController.java
customer/api/CustomerModuleApi.java
customer/application/OperatorCustomerLookupService.java
shared/idempotency/IdempotencyModuleApi.java
shared/idempotency/IdempotencyRecordRepository.java
```

`AccountModuleApi` returns immutable `AccountView` and operation results; it never exports JPA entities/repositories. `OperatorCustomerLookupService` calls `CustomerModuleApi` for exact identity then `AccountModuleApi` for account views; it does not join repositories across modules. `AccountRepository` owns its account queries; `SeedRecordRepository` owns seed ledger reads/writes.

## Query behavior and privacy

### Account reads

- `GET /accounts` derives user/customer identity from authenticated principal; customer module resolves customer ID, account module selects only matching customer accounts ordered deterministically by `opened_at DESC, id DESC` and returns OpenAPI `AccountPage` cursor behavior (inspect schema before choosing cursor; do not invent fields).
- `GET /accounts/{id}` loads account through account module; service checks owner. Unauthorized owner behavior must follow OpenAPI 403/404 and security documentation; avoid using controller role check as ownership substitute.
- Account response uses masked account number only, `MoneyAmount` canonical integer string, VND and openedAt. Never return full account number.

### Operator exact lookup

- Require exactly one query param: non-empty `phone` XOR `email`; both/none is `400 VALIDATION_ERROR`.
- Exact normalized equality only. Phone validates `^0[3-9][0-9]{8}$`; email trims/lowercases with `Locale.ROOT`. No LIKE, wildcard, list, prefix or account-number search.
- Apply Phase 02 operator ID rate limit before lookup. Audit every lookup with actor, target if found, outcome, correlation ID; do not log raw search value. Not-found is 404 per OpenAPI.
- Response `OperatorCustomerView` includes profile and masked account objects. Do not return password, PIN state beyond `isPinSet`, OTP, token, internal metadata.

## Seed balance transaction and idempotency

`SeedBalanceService.seed(Actor, UUID accountId, SeedBalanceRequest, String key, UUID correlationId)` runs one PostgreSQL transaction:

1. Validate accountId and exact OpenAPI request; `amount` canonical integer string; parse to `BigDecimal` scale 0 with no rounding; enforce `0 < amount <= 100000000`, currency VND, reference 1..100.
2. Compute canonical request hash from operation version + accountId + normalized amount + currency + exact reference. Never include token/PIN.
3. Claim `(actorId, operation="seedAccountBalance", key)` in `idempotency_records`. Use unique index; on conflict load existing row. Same hash returns stored response with HTTP 200 and `Idempotency-Replayed: true`; different hash returns 409 `IDEMPOTENCY_KEY_REUSED` (or exact OpenAPI code) and no mutation.
4. Lock account using parameterized native SQL: `SELECT ... FROM accounts WHERE id = :id FOR UPDATE`. Verify eligible status per contract (ACTIVE only; BLOCKED cannot seed), VND and valid owner-independent Operator policy.
5. Insert `account_seed_records`; update exact balance `balance = balance + :amount` and `updated_at`; append audit fact through `AuditModuleApi`; store original response status/body/resource ID and expiry >=24h in idempotency row.
6. Commit once; return response with 201 and `Idempotency-Replayed: false`.

The idempotency row, seed ledger row, balance update and audit fact must share the same transaction. If failure occurs before commit, all roll back. Concurrent same-key insert loser retries read after unique conflict; avoid catching an integrity exception inside a transaction already marked rollback-only—use a dedicated insert-on-conflict SQL strategy or transaction retry at service boundary.

## Block/unblock state transitions

`ChangeAccountStatusService.changeStatus(actor, accountId, targetStatus, reason, correlationId)`:

1. Validate reason nonblank, trim; preserve no more than 500 chars; request DTO uses exact OpenAPI schema.
2. Start transaction; lock account row `SELECT ... FOR UPDATE`.
3. If CLOSED: `409 ACCOUNT_NOT_ELIGIBLE`; do not mutate.
4. If already target state: return current Account; do not update timestamp or append duplicate mutation audit.
5. Else update status, updated_at, append audit fact with old/new status, operator/admin actor, reason and correlation ID in same transaction; commit and return 200.

This lock serializes status changes with transfer account locks in Phase 05. All code paths locking two accounts must use `ORDER BY id ASC FOR UPDATE`; one-account transitions naturally lock that account only.

## DDL and indexes

Use `accounts`/`account_seed_records` DDL from V1/V3 in roadmap. Account IDs/customer IDs and actor IDs remain scalar module references without cross-module FK. Ensure repository updates `NUMERIC(19,0)` exactly. Index support: `accounts(customer_id, opened_at DESC, id DESC)`, unique default account partial index, `account_seed_records(account_id, created_at DESC, id DESC)`, `idempotency_records(actor_id, operation, idempotency_key)` unique.

## Tests and exit gate

- MockMvc success/error contract tests for all seven operations, exact JSON schema/status/header/media type.
- Role: customer/auditor cannot operator lookup/seed/block/unblock; admin/operator allowed as specified.
- Account IDOR and masking tests; account list only caller's data.
- Lookup none/both/invalid/valid filters, normalization, exact no-wildcard behavior, not-found response, rate limit 429/Retry-After.
- Seed min=1/max=100M/zero/over max/fraction/leading-zero/negative/currency/status cases; verify no mutation on rejection.
- Idempotency same key/same payload returns same result once; altered amount/reference/account conflicts; concurrent identical requests create one ledger row and one credit.
- Block/unblock transitions, reason validation, CLOSED rejection, repeated same state no duplicate audit, audit failure rolls back state.
- PostgreSQL Testcontainers: account lock, atomic seed rollback and concurrency; never shared DB.

## Implementation loop

Inspect actual OpenAPI `AccountPage`, account role/security policy, seed response/idempotency details and Phase 02 limiter/audit APIs. No broad search. If contract leaves cursor schema unspecified for accounts, preserve existing contract without inventing cursor fields and ask only if implementation cannot conform. After user approves Phase 04, implement query paths then seed then status changes, add tests, review privacy/atomicity, record evidence and stop before Phase 05.

**Verification record:** pending implementation approval.
