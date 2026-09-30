# MVP Open Decisions Record

## Decision baseline

These decisions are approved for MVP implementation and are designed to preserve an upgrade path.

| Topic | MVP choice | Why | Later expansion |
|---|---|---|---|
| Recipient selection | `POST /recipients/resolve` by full account number; return masked account + limited display name. | Supports FE confirmation without exposing account directory. | Add contacts/beneficiary model and aliases; transfer still uses stable `destinationAccountId`. |
| Operator customer lookup | MVP: `GET /operator/customers?phone=` or `?email=` — exactly one exact filter, returns customer + accounts, audited, rate limited. No listing, partial or wildcard search. | Operator needs `accountId` for seed/block after approval queue was removed, without enabling bulk enumeration. | Add audited broader support search after operational requirements are proven. |
| Account status | MVP: Operator/Admin `block`/`unblock` (`ACTIVE ⇄ BLOCKED`) with reason; idempotent; `CLOSED` terminal. | Completes "customer/account management" use case and enables locked-account demo. | Account closure workflow, customer self-freeze. |
| Transfer status | `AWAITING_OTP`, `COMPLETED`, `EXPIRED`, `FAILED`; no funds reserved while awaiting OTP; re-validate under lock at confirm; confirm idempotent by `transferId`. | Makes "transaction status" observable and keeps step-up consistent with single-transaction money rule. | Fund hold/reservation, async settlement states. |
| Recovery anti-enumeration | Recovery initiate always generic `200`; confirm with unknown identifier = `400 OTP_INVALID`; async OTP dispatch. Registration keeps `409` as **accepted risk** with rate limit. | Prevents account discovery on the anonymous recovery flow while keeping registration UX clear. | Registration `send-otp` always `200` + "already registered" SMS. |
| Risk review workflow | Post-MVP option: review endpoint, notes and `REVIEWED` status. MVP flags are read-only and remain `OPEN`. | Detection is required; case-management workflow is not required for first vertical slice. | Add reviewer ownership, audit and state-transition rules later. |
| Customer identity | `phone` (VN 10 digits) is login identifier; `email` also required; both UNIQUE. Self-registration verifies phone by OTP; Operator counter creation also requires customer OTP + email. No approval queue. | Matches real retail-banking UX; two channels enable self-service recovery. | Add eKYC/document verification before any real-identity use. |
| Account recovery | Forgot password via OTP on chosen channel (`SMS` or `EMAIL`), OTP TTL 120s; success revokes all refresh tokens. | Self-service recovery without support staff; force logout contains takeover risk. | Add device binding / risk-based step-up. |
| Transfer authentication | 6-digit PIN for every transfer; extra SMS OTP above `5,000,000` VNĐ. | Tiered step-up keeps small transfers fast while protecting large ones. | Smart OTP / biometric authenticator. |
| Demo identities/data | Local `demo` profile seeds fixed Operator/Auditor plus at least two active Customers/accounts. New Customer follows registration (or counter creation) → seed flow. | Reproducible classroom demo; no privileged-account creation API. | Replace local seed with managed bootstrap/job/admin provisioning. |
| Auth/session | Short-lived access token in memory; rotated HttpOnly refresh cookie; CSRF header on refresh/logout; same-origin proxy default. | Avoids persistent bearer token in browser and reduces CORS/CSRF complexity. | Move identity to managed IdP; keep bearer/API contract and role claims stable. |
| Idempotency | Transfer and seed require scoped key + canonical payload hash; same key/payload replays; changed payload conflicts; retention >= 24h. | Demonstrates retry safety and works for future external payment adapters. | Shared idempotency component/outbox across payment rails. |
| Money | Simulated VND only; JSON decimal/integer string; scale 0; transfer min `2,000` VNĐ, max `10,000,000` VNĐ; seed max `100,000,000` VNĐ (min > 0). | Lowest ambiguity for FE and easiest deterministic test/load demo. | Version contract before multi-currency; use currency-specific minor units and FX domain later. |
| Recipient privacy | Same not-available result for nonexistent/ineligible recipient; rate limit. | Prevents account enumeration while keeping transfer UX usable. | Add authenticated beneficiary directory with stronger verification. |
| Server state | TanStack Query for remote data; Zustand for UI/session presentation state. | Separates cache/invalidation from UI state; avoids stale authoritative balances. | Shared query/cache policy remains usable when backend splits into services. |
| Risk rules | Flag after committed transfer; amount threshold + frequency window; no blocking; read-only flag query. | Easy to explain and test; does not make risk heuristic part of money correctness. | Review workflow, versioned rules engine or ML scorer after explicit need and dataset evaluation. |
| Pagination | Cursor pagination ordered by `(createdAt DESC, id DESC)`; limit 1..100, default 20. | Stable under inserts and scales better than offset pagination. | Service/search index can preserve cursor contract. |
| Integration | OpenAPI YAML is source of truth; generated FE types/client and mock; CI validates contract. | Prevents FE/BE drift and enables parallel development. | Publish versioned API artifact/registry and compatibility checks. |

## Demo seed requirements

`demo` environment must provide:

- One `OPERATOR` user.
- One `AUDITOR` user.
- Two active `CUSTOMER` users with distinct phone/email, PIN already set, and one active `CHECKING` account each.
- Different account numbers and enough simulated VND balance for transfer demo.
- Credentials supplied through local environment/secret mechanism, never committed in Git.
- Seed script idempotent: rerun does not duplicate users, accounts or balances.

## Configuration baseline

```text
APP_CURRENCY=VND
TRANSFER_MIN_AMOUNT=2000
TRANSFER_MAX_AMOUNT=10000000
SEED_MAX_AMOUNT=100000000
IDEMPOTENCY_RETENTION_HOURS=24
RISK_LARGE_TRANSFER_THRESHOLD=5000000
RISK_TRANSFER_COUNT_WINDOW_MINUTES=10
RISK_TRANSFER_COUNT_THRESHOLD=5
OTP_LENGTH=6
OTP_TTL_SECONDS=120
OTP_MAX_ATTEMPTS=5
TRANSFER_OTP_THRESHOLD=5000000
PIN_MAX_FAILED_ATTEMPTS=5
PIN_LOCK_MINUTES=15
```

Names are configuration intent. Backend implementation may use equivalent property names, but OpenAPI and deployment docs must expose effective values.

## Contract closure checklist

Before FE/BE split work starts:

- [ ] Commit `contracts/openapi.yaml` and validate syntax/schema.
- [ ] Generate FE types/client and mock fixtures from same file.
- [ ] Confirm exact-origin/CORS settings for non-same-origin local mode, or use same-origin proxy.
- [ ] Implement `/auth/csrf` and document `credentials: include` for refresh/logout.
- [ ] Add recipient resolve endpoint and rate-limit behavior.
- [ ] Add demo seed script/profile and non-secret credential instructions.
- [ ] Add contract tests for every endpoint's success/error status.
- [ ] Add FE states for all stable error codes.
- [ ] Freeze currency, amount limits, idempotency retention and risk thresholds in environment config.
- [ ] Run E2E: register (phone OTP) → login by phone → PIN setup → seed → resolve recipient → transfer → history; forgot password via SMS and via Email.
- [ ] POST-MVP infrastructure remains out of scope.

## Non-goals retained

Do not add customer deposit/withdrawal, external payment, multi-currency, ML fraud blocking, notification delivery, microservices or Kubernetes before MVP acceptance gates pass.
