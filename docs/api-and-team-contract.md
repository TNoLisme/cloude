# API & Team Contract

> **Contract status:** MVP implementation baseline. `../contracts/openapi.yaml` is normative for paths, schemas, status codes and security schemes. This guide adds FE call sequences, state behavior and implementation rules. If this guide and OpenAPI differ, update both in same change; do not guess in code.

## 1. Frontend integration summary

### Base URL

- Local backend: `http://localhost:8080/api/v1`
- FE reads base URL from `VITE_API_BASE_URL`; do not hard-code deployment host.
- Local dev and MVP demo use same-origin Vite proxy/reverse proxy at `/api`; production FE uses same-origin ingress by default.
- Local same-origin proxy is default; if different origin is required, set exact allowlist, credentials support and CSRF validation. Wildcard CORS with credentials is forbidden.
- All successful request/response bodies are JSON except `204 No Content`.

### Common request headers

| Header | Required | Meaning |
|---|---:|---|
| `Accept: application/json` | Yes | FE expects JSON response. |
| `Content-Type: application/json` | When body exists | JSON request payload. |
| `Authorization: Bearer <accessToken>` | Authenticated APIs | Access token issued by login/refresh. |
| `X-CSRF-Token: <csrfToken>` | Cookie-authenticated refresh/logout | CSRF token issued by auth bootstrap; required with refresh cookie. |
| `X-Correlation-Id: <uuid>` | Optional | FE may generate per user action; BE returns accepted value. FE retains it for support/debug display only. |
| `Idempotency-Key: <uuid>` | `POST /transfers`, `POST /operator/accounts/{accountId}/seed-balance` | Stable key per logical money mutation. Reuse same key after network timeout. |

Do not send actor ID, role, owner ID or balance in headers/body as proof of authority. BE derives actor and role from validated access token.

### Common response headers

- `X-Correlation-Id`: request trace identifier.
- `Cache-Control: no-store` for auth responses and private financial data unless caching policy is explicitly revised.
- `Retry-After`: optional on `429` or temporary `503` responses.

### FE API client contract

Create one typed client in `frontend/src/api/`:

- Reads `VITE_API_BASE_URL`.
- Adds bearer token for authenticated requests.
- Adds JSON headers only when sending JSON body.
- Adds or forwards correlation ID.
- Parses `Problem` response into one typed `ApiError` preserving `status`, `code`, `detail`, `fieldErrors`, and `correlationId`.
- On `401`, clear in-memory session and route to login. Do not retry unsafe mutations automatically.
- On `403`, show access denied; do not infer resource absence.
- On `409` transfer/seed idempotency replay, reconcile returned original result. On unknown network failure, retain key and exact payload for safe retry.
- On mutation `429/503`, honor `Retry-After`; only retry when endpoint semantics are safe and preserve idempotency key.
- FE never calculates authoritative balance or decides access rights.

## 2. Contract-first workflow

1. Agree path, schema, role, state transition and error behavior before parallel work.
2. Change `contracts/openapi.yaml` first. Review breaking changes with FE and BE owners.
3. Validate OpenAPI and generate/check TypeScript client/types in CI.
4. FE develops screens using mocks from this contract, including pending/loading/error/empty cases.
Prism generates schema-shaped example responses, but does not implement business behavior such as ownership, idempotency, concurrency or state transitions. Use MSW fixtures for deterministic success, business-error and loading scenarios. Keep fixtures valid against OpenAPI.
5. BE implements controller/use case and contract tests against same schemas/status codes.
6. FE switches mock to API and runs integration/E2E flow.
7. Merge only when contract validation, FE build/typecheck, BE contract tests and targeted end-to-end flow pass.

`contracts/openapi.yaml` is intended to be committed with the repo. This docs-only change describes its required location and contract but does not create repo implementation files.

## 3. API-wide conventions

- Prefix: `/api/v1`.
- IDs: UUID strings.
- Timestamp: RFC 3339 UTC ending in `Z`.
- Money: decimal string, e.g. `"125.40"`; never JSON float. MVP uses simulated USD only, scale 2. Transfer amount maximum `10,000.00`; seed balance maximum `100,000.00`. Values are configurable only by backend environment, not client request.
- `currency` must be `USD` in MVP and match account currency. Contract versioning is required before adding another currency/scale.
- Enum values: uppercase snake case.
- Unknown response fields: FE must ignore. BE may add optional response fields without breaking clients.
- Request schema: BE rejects unknown or invalid required fields with `400 VALIDATION_ERROR`; OpenAPI `additionalProperties: false` for mutation request objects.
- Empty list: `200` with `items: []`, not `404`.
- List response uses `{ "items": [], "nextCursor": null }`; `limit` defaults to `20`, range `1..100`.
- Date filters are inclusive UTC instants: `from` <= event time < `to`.
- No credentials, hashes, internal keys or unmasked sensitive data in API responses.

## 4. Roles and access rules

| API group | Anonymous | Customer | Operator | Auditor | Admin |
|---|---:|---:|---:|---:|---:|
| Register/login | Yes | Yes | Yes | Yes | Yes |
| `/customers/me`, own accounts/transfers | No | Own data | No | No | No by default |
| Operator onboarding queue/decision | No | No | Yes | No | Yes |
| Operator seed balance | No | No | Yes | No | Yes |
| Audit query | No | No | No | Yes | Yes |
| Risk-flag query | No | No | Yes | Yes | Yes |
| Health | Deployment-configured | Deployment-configured | Deployment-configured | Deployment-configured | Deployment-configured |

Roles are not interchangeable. BE checks resource ownership for every Customer request. Admin does not bypass money invariants. Hiding a resource from unauthorized actors may return `404` instead of `403`, but behavior must be consistent per resource type and must not leak existence.

## 5. Endpoint catalog

OpenAPI file defines exact JSON schemas and status responses. This table distinguishes MVP endpoints from post-MVP options.

| Method and path | Access | Purpose / success |
|---|---|---|
| `POST /auth/register` | Anonymous | Create account credentials and `PENDING` customer profile; `201`. No bank account created. |
| `POST /auth/login` | Anonymous | Verify credentials; return `accessToken`, expiry and actor summary; `200`. |
| `POST /auth/refresh` | Refresh token | Rotate refresh token and return new access token; `200`. |
| `POST /auth/logout` | Authenticated | Revoke current refresh session; `204`. |
| `GET /auth/csrf` | Anonymous/session | Issue CSRF token for cookie-authenticated refresh/logout; `200`. |
| `POST /recipients/resolve` | Customer | Resolve full account number into masked recipient confirmation; `200`. |
| `GET /customers/me` | Customer | Return own customer profile/onboarding state; `200`. |
| `GET /operator/onboarding` | Operator/Admin | List applications, default `PENDING`; `200`. |
| `GET /operator/onboarding/{customerId}` | Operator/Admin | Read application details; `200`. |
| `POST /operator/onboarding/{customerId}/approve` | Operator/Admin | Approve and create exactly one default account atomically; `200`. |
| `POST /operator/onboarding/{customerId}/reject` | Operator/Admin | Reject pending application with reason; `200`. |
| `GET /accounts` | Customer | List own accounts/balances; `200`. |
| `GET /accounts/{accountId}` | Owner, Operator/Admin by policy | Read account; `200`. |
| `POST /operator/accounts/{accountId}/seed-balance` | Operator/Admin | Credit demo opening funds exactly once per request key; `201`. |
| `POST /transfers` | Customer | Create/replay internal transfer; `201` new, `200` replay. Requires `Idempotency-Key`. |
| `GET /transfers` | Customer | List transfers where caller owns source or destination account; `200`. |
| `GET /transfers/{transferId}` | Participant; Operator/Auditor/Admin by policy | Read transfer state/details; `200`. |
| `GET /audit-events` | Auditor/Admin | Search allowed audit events; `200`. |
| `GET /risk-flags` | Operator/Auditor/Admin | List read-only suspicious flags; MVP flags remain `OPEN`. |
| `GET /health` | Platform | Liveness/readiness, deployment exposure controlled; `200` or `503`. |

No Customer deposit/withdrawal API. No public endpoint directly sets balance outside seed-balance and transfer use cases.

## 6. Endpoint details and FE call behavior

### 6.1 Register

`POST /api/v1/auth/register`

Request:

```json
{
  "email": "alex@example.test",
  "password": "Example-only-password",
  "fullName": "Alex Example",
  "phone": "+84901234567",
  "address": "123 Cau Giay, Hanoi"
}
```

Response `201`:

```json
{
  "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
  "email": "alex@example.test",
  "fullName": "Alex Example",
  "onboardingStatus": "PENDING",
  "createdAt": "2026-09-28T10:00:00Z"
}
```

FE behavior: on success show pending-review state; do not show account/balance creation. Duplicate email returns `409 EMAIL_ALREADY_REGISTERED`. Validation returns field errors. Registration does not log user in unless contract is explicitly changed.

### 6.2 Login and session refresh

`POST /api/v1/auth/login`

Request:

```json
{ "email": "alex@example.test", "password": "Example-only-password" }
```

Response `200`:

```json
{
  "accessToken": "<opaque-example-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "userId": "ad1f67a1-6a9f-4c11-a3c4-bc01f6ac4350",
    "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
    "displayName": "Alex Example",
    "roles": ["CUSTOMER"],
    "onboardingStatus": "PENDING"
  }
}
```

`refresh_token` cookie uses `HttpOnly`, `Secure` on HTTPS, `SameSite=Lax`. Access token stays in memory only.

`POST /api/v1/auth/refresh`: no JSON body; sends refresh cookie; response has new `accessToken`, `tokenType`, `expiresIn`. Server rotates cookie. Invalid/expired/revoked refresh session returns `401 SESSION_EXPIRED` and clears cookie.

`POST /api/v1/auth/logout`: sends refresh cookie and bearer token; revokes current session, clears cookie; `204`.

FE behavior: hold access token in memory only; do not persist token in `localStorage`/`sessionStorage`. On initial app load, attempt refresh once; if it returns `401`, show login. A single concurrent refresh mechanism may be used by API client. Retry original request only once after successful refresh, and only when request body can be safely replayed. Transfer retry always preserves idempotency key.

### 6.3 Current customer

`GET /api/v1/customers/me`

Response `200`: `customerId`, `fullName`, `email`, `onboardingStatus`, `createdAt`, optional `decisionAt`, optional `decisionReason`. No password/auth internals.

FE behavior: `PENDING` shows waiting state, `REJECTED` shows safe reason if policy allows, `APPROVED` enables account features. BE still enforces all endpoint access; FE route guards are not security controls.

### 6.4 Operator onboarding queue and detail

`GET /api/v1/operator/onboarding?status=PENDING&limit=20&cursor=<opaque>`

Query:

- `status`: `PENDING|APPROVED|REJECTED`; default `PENDING`.
- `limit`: integer `1..100`; default `20`.
- `cursor`: opaque, optional.

Response `200`:

```json
{
  "items": [
    {
      "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
      "fullName": "Alex Example",
      "email": "alex@example.test",
      "onboardingStatus": "PENDING",
      "submittedAt": "2026-09-28T10:00:00Z"
    }
  ],
  "nextCursor": null
}
```

`GET /api/v1/operator/onboarding/{customerId}` returns queue item plus fields required for simulator review. Do not include credentials or sensitive fields not required for review.

FE behavior: show loading, empty queue, page error and data states. After approve/reject, remove/update row locally only after successful response, or refetch queue.

### 6.5 Approve/reject onboarding

Approve: `POST /api/v1/operator/onboarding/{customerId}/approve`

Request:

```json
{ "decisionNote": "Simulator review completed" }
```

Response `200`:

```json
{
  "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
  "onboardingStatus": "APPROVED",
  "account": {
    "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
    "accountNumberMasked": "••••4821",
    "accountType": "CHECKING",
    "status": "ACTIVE",
    "balance": "0.00",
    "currency": "USD"
  },
  "decidedAt": "2026-09-28T10:15:00Z"
}
```

Reject: `POST /api/v1/operator/onboarding/{customerId}/reject`

Request:

```json
{ "reason": "Required simulator profile details are incomplete" }
```

Response `200` contains customer ID, `REJECTED`, decision timestamp and safe reason.

Common errors: `404 CUSTOMER_NOT_FOUND`; `409 ONBOARDING_ALREADY_DECIDED`; `403 FORBIDDEN`.

Retry behavior: same decision repeated after successful commit returns current decision/result without duplicate account. Opposite decision after terminal state returns `409 ONBOARDING_ALREADY_DECIDED`. Approve and account creation are atomic.

### 6.6 Accounts and balances

`GET /api/v1/accounts`

Response `200`:

```json
{
  "items": [
    {
      "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
      "accountNumberMasked": "••••4821",
      "accountType": "CHECKING",
      "status": "ACTIVE",
      "balance": "1000.00",
      "currency": "USD",
      "openedAt": "2026-09-28T10:15:00Z"
    }
  ],
  "nextCursor": null
}
```

`GET /api/v1/accounts/{accountId}` returns same account shape plus owner display data only if authorized. Customer may only query own account. Unknown and unauthorized account response follows consistent concealment policy.

### Post-MVP account search option

A future `GET /api/v1/operator/accounts?customerId=<uuid>&accountNumber=<string>` may support operational lookup. It is not part of MVP endpoint catalog or implementation. If approved later, require at least one exact filter, reject unfiltered listing, return masked account numbers and audit access.

FE behavior: account list is server state. Refetch after seed/transfer events; never persist balance as authoritative state in Zustand. MVP Operator flow uses account ID from approval response; operational account search is post-MVP.

### 6.7 Operator seed balance

`POST /api/v1/operator/accounts/{accountId}/seed-balance`

Headers: `Authorization`, `Idempotency-Key`, optional `X-Correlation-Id`.

Request:

```json
{
  "amount": "1000.00",
  "currency": "USD",
  "reference": "DEMO-OPENING-BALANCE"
}
```

Response `201`:

```json
{
  "seedTransactionId": "cc168d4a-12cd-4f0f-8c63-55b90ad1e310",
  "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "amount": "1000.00",
  "currency": "USD",
  "balanceAfter": "1000.00",
  "createdAt": "2026-09-28T10:20:00Z"
}
```

FE behavior: Operator confirms action in UI and labels it “Demo funds”. Update displayed balance only from response/refetch. Retry after network timeout with same key.

Errors: `404 ACCOUNT_NOT_FOUND`; `409 ACCOUNT_NOT_ELIGIBLE`, `IDEMPOTENCY_KEY_REUSED`; `400 AMOUNT_INVALID` or `CURRENCY_MISMATCH`.

### 6.8 Resolve recipient

`POST /api/v1/recipients/resolve`

Headers: `Authorization: Bearer <accessToken>`, `Content-Type: application/json`.

Request:

```json
{
  "accountNumber": "1002003004"
}
```

Response `200`:

```json
{
  "accountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "accountNumberMasked": "••••3004",
  "recipientDisplayName": "Bob Counterparty",
  "currency": "USD"
}
```

`POST /api/v1/recipients/resolve` is authenticated and rate-limited. It returns a limited `recipientDisplayName` for confirmation. Do not expose email, phone, address or full account number. Nonexistent and ineligible account numbers return the same `404 RECIPIENT_NOT_AVAILABLE` Problem code.

### 6.9 Create internal transfer

`POST /api/v1/transfers`

Headers:

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 3be4ec14-c219-4e50-a486-15dcc4d4a879
X-Correlation-Id: 47ec3533-9b7f-408c-a143-ab3918e56446
```

Request:

```json
{
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "25.00",
  "currency": "USD",
  "memo": "Shared lunch"
}
```

Response `201` for first committed transfer:

```json
{
  "transferId": "7a2327ae-b132-44a5-b682-982842717213",
  "status": "COMPLETED",
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "25.00",
  "currency": "USD",
  "memo": "Shared lunch",
  "createdAt": "2026-09-28T10:30:00Z",
  "completedAt": "2026-09-28T10:30:00Z"
}
```

`COMPLETED` response means source debit and destination credit committed. MVP has no externally pending transfer state. A rejected business operation returns Problem Details and no transfer balance mutation.

FE behavior:

1. Validate required fields/format for UX; BE validation remains authoritative.
2. Generate idempotency key once when user confirms submission.
3. Disable duplicate submit while request is in flight, but do not rely on UI lock for correctness.
4. On `201`, show confirmation and refetch balances/history.
5. On timeout/network error, keep same key and exact request body. Offer retry/status reconciliation; do not generate a new key automatically.
6. On `409 INSUFFICIENT_FUNDS`, show field/form error and refresh balance.
7. On `409 IDEMPOTENCY_KEY_REUSED`, do not retry with that key and changed body; explain request conflict and start a new intentional operation with a new key only after user action.

Transfer idempotency rules:

- Same actor + same operation + same key + same canonical payload: return original logical transfer/seed response; no second balance mutation. First request returns `201`; replay returns `200` with `Idempotency-Replayed: true` response header.
- Same key but different payload: `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent duplicates: exactly one transfer/seed record and one balance mutation.
- Retain keys for at least 24 hours; keep financial record under standard demo retention. Do not purge key while result may need reconciliation.
- Business rejection does not reserve successful result; corrected request requires new key.
- Approve/reject does not require idempotency header: same terminal decision returns current result; opposite decision returns `409 ONBOARDING_ALREADY_DECIDED`.
- Key length: 16..128 printable ASCII characters; generated UUID recommended.

### 6.10 Transfer history and detail

`GET /api/v1/transfers?limit=20&cursor=<opaque>&status=COMPLETED&from=<RFC3339>&to=<RFC3339>`

- All query parameters optional.
- `status`: `COMPLETED` in synchronous MVP; future values must be additive and documented.
- `from` inclusive; `to` exclusive; reject `from >= to`.
- Result only includes transfers where current Customer owns source or destination account.
- Stable order: `createdAt` descending, then `transferId` descending.

Response `200`:

```json
{
  "items": [
    {
      "transferId": "7a2327ae-b132-44a5-b682-982842717213",
      "direction": "OUTGOING",
      "status": "COMPLETED",
      "counterpartyAccountMasked": "••••7710",
      "counterpartyDisplayName": "Bob Counterparty",
      "amount": "25.00",
      "currency": "USD",
      "memo": "Shared lunch",
      "createdAt": "2026-09-28T10:30:00Z"
    }
  ],
  "nextCursor": null
}
```

Transfer history uses a dedicated lightweight `TransferListItem` shape. It does not expose source/destination account IDs in list rows. `counterpartyDisplayName` is limited display data and must not include email, phone or address.



FE behavior: detail route can load directly by ID; do not require history screen to have populated Zustand first. Handle missing/unauthorized as not found.

### 6.11 Audit events

`GET /api/v1/audit-events?limit=20&cursor=<opaque>&eventType=TRANSFER_COMPLETED&actorId=<uuid>&from=<RFC3339>&to=<RFC3339>`

Auditor/Admin only. Supported filters are optional. Response has `items` and `nextCursor`; each item includes `eventId`, `eventType`, `actorId` (may be system), `targetType`, `targetId`, `outcome`, `occurredAt`, `correlationId`, and redacted `summary`. No raw token, password, secret or full sensitive request payload.

FE behavior: display read-only event details; no edit/delete endpoint exists. Unauthorized role receives `403 FORBIDDEN`.

### 6.12 Risk flags (MVP read-only)

`GET /api/v1/risk-flags?status=OPEN&ruleId=<string>&limit=20&cursor=<opaque>`

Operator/Auditor/Admin only. MVP flags have status `OPEN`; response includes `flagId`, `transferId`, `ruleId`, `ruleVersion`, `reason` and `detectedAt`.

Risk-flag review, notes and `REVIEWED` status are post-MVP options. MVP does not expose a review mutation endpoint.


## 7. Standard error contract

All errors use `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Business rule conflict",
  "status": 409,
  "detail": "Available balance is not sufficient for this transfer.",
  "instance": "/api/v1/transfers",
  "code": "INSUFFICIENT_FUNDS",
  "correlationId": "47ec3533-9b7f-408c-a143-ab3918e56446",
  "fieldErrors": []
}
```

`fieldErrors` is optional array `{ "field": "amount", "code": "MINIMUM", "message": "Amount must be greater than zero." }`. FE branches on `code`, not localized `title/detail`.

| HTTP | Stable codes (minimum) | FE behavior |
|---|---|---|
| `400` | `VALIDATION_ERROR`, `AMOUNT_INVALID`, `CURRENCY_MISMATCH`, `INVALID_DATE_RANGE` | Show field/form errors; do not retry unchanged request. |
| `401` | `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` | Refresh once if eligible; otherwise clear session/login. |
| `403` | `FORBIDDEN` | Show access denied; do not retry. |
| `404` | `CUSTOMER_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `TRANSFER_NOT_FOUND`, concealed resource code | Show not found; avoid exposing whether protected resource exists. |
| `409` | `EMAIL_ALREADY_REGISTERED`, `ONBOARDING_ALREADY_DECIDED`, `ACCOUNT_NOT_ELIGIBLE`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `STATE_CONFLICT` | Show business conflict; refresh affected server state. |
| `429` | `RATE_LIMITED` | Respect `Retry-After`; never rapid-loop. |
| `500` | `INTERNAL_ERROR` | Show generic message + correlation ID; no unsafe automatic mutation retry. |
| `503` | `SERVICE_UNAVAILABLE` | Show temporary unavailable; safe retry only; transfer retry must reuse key. |

Validation framework details and stack traces never reach FE. BE returns generic `500` with correlation ID and logs diagnostic details securely.

## 8. FE navigation and API call sequences

### Customer onboarding

```text
Register screen
  POST /auth/register
  201 -> Pending approval screen
  409 EMAIL_ALREADY_REGISTERED -> registration form error

Login screen
  POST /auth/login
  200 -> save access token in memory; route by roles/status
  PENDING -> pending screen
  REJECTED -> rejected status screen
  APPROVED -> dashboard
```

### Operator approval and seed funds

```text
Operator onboarding queue
  GET /operator/onboarding?status=PENDING
  GET /operator/onboarding/{customerId}
  POST .../approve OR POST .../reject
  Approval response returns default accountId
  POST /operator/accounts/{accountId}/seed-balance (Idempotency-Key)
```

FE must not call seed endpoint before approval response returns account ID. If response is lost, reload detail/queue and reconcile before retrying with same seed idempotency key.

### Customer transfer

```text
Dashboard
  GET /accounts
  GET /transfers?limit=20

Transfer form
  GET /accounts (or use fresh cached account list)
  POST /transfers (Idempotency-Key)
  201/200 -> GET /accounts + GET /transfers
  network timeout -> retry same body/key; never assume failure
```

## 9. Zustand and server-state ownership

Use **TanStack Query** for server state and **Zustand** for client/UI state. Both can be used together without duplicating ownership.

- Current UI state: navigation, modal/drawer, form draft before submission, table preferences.
- Auth presentation state: current user summary and access token held in memory; clear on logout/refresh failure.

Zustand must not be sole source of truth for:

- Balance, account status, transfer status/history, onboarding decision, risk flag or audit record.
- Permission enforcement.

TanStack Query owns server responses, loading/error state, stale time, in-flight request deduplication, retries for safe reads and cache invalidation. After approve/reject invalidate onboarding queries; after seed/transfer invalidate accounts and history. Disable automatic retry for money mutations unless retry reuses the exact idempotency key and body. Optimistic balance updates are not allowed for MVP.

## 10. OpenAPI operation IDs and generated types

- Every operation has stable unique `operationId`, e.g. `registerCustomer`, `createTransfer`, `getTransferHistory`.
- FE API client/types derive from `contracts/openapi.yaml`.
- Do not hand-edit generated output; update source contract and regenerate.
- CI fails for invalid OpenAPI, duplicate operation IDs or generated diff.
- Use shared schema refs for `Problem`, `Page<T>`, `Money`, `CustomerStatus`, `Account`, `Transfer`.
- Examples in this guide are illustrative; test fixtures must conform to OpenAPI validation.

## 11. Contract compatibility rules

Safe additive changes: optional response property, new enum value only when FE has unknown-value fallback, new endpoint. Breaking changes: remove/rename field/path, change type/meaning, make optional field required, alter status/error behavior or auth requirements.

Breaking change process:

1. Propose contract diff and affected FE screens.
2. Update contract and FE/BE together, or support old/new versions temporarily.
3. Add/adjust contract tests and mocks.
4. Merge coordinated change only after both builds pass.

No silent contract drift. Update OpenAPI, this guide and mock fixtures in same PR.

## 12. Team ownership and merge gates

### FE can implement independently when

- OpenAPI path/schema/status/auth is approved.
- Mock examples cover success, empty, loading, field validation, unauthorized, forbidden, business conflict and unavailable states.
- FE build/typecheck succeeds without running BE.

### BE can implement independently when

- OpenAPI schema and state transitions are approved.
- Contract tests assert status, headers, schema and error code.
- Ownership, roles, idempotency and money invariants have targeted tests.

### Integration merge gate

- OpenAPI validation passes.
- FE generated client/types are current and FE build/typecheck passes.
- BE contract/integration tests pass.
- E2E onboarding → approval → seed balance → transfer → history succeeds in isolated environment.
- No endpoint, field or status divergence exists between OpenAPI, BE and FE mocks.
