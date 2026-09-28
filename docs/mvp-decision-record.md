# MVP Open Decisions Record

## Decision baseline

These decisions are approved for MVP implementation and are designed to preserve an upgrade path.

| Topic | MVP choice | Why | Later expansion |
|---|---|---|---|
| Recipient selection | `POST /recipients/resolve` by full account number; return masked account + limited display name. | Supports FE confirmation without exposing account directory. | Add contacts/beneficiary model and aliases; transfer still uses stable `destinationAccountId`. |
| Operator account search | Post-MVP option: `GET /operator/accounts` with required exact filter. MVP seeds using `accountId` returned by approval response. | Keeps MVP flow small and avoids unfiltered operational account enumeration. | Add audited support search after operational requirements are proven. |
| Risk review workflow | Post-MVP option: review endpoint, notes and `REVIEWED` status. MVP flags are read-only and remain `OPEN`. | Detection is required; case-management workflow is not required for first vertical slice. | Add reviewer ownership, audit and state-transition rules later. |
| Demo identities/data | Local `demo` profile seeds fixed Operator/Auditor plus at least two approved Customers/accounts. New Customer still follows registration → approval → seed flow. | Reproducible classroom demo; no privileged-account creation API. | Replace local seed with managed bootstrap/job/admin provisioning. |
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
- Two approved `CUSTOMER` users with one active `CHECKING` account each.
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
- [ ] Run E2E: register → approve → seed → resolve recipient → transfer → history.
- [ ] Keep post-MVP options out of MVP code: operator account search, risk review, outbox, Kafka, Saga, read replica and microservices.

## Non-goals retained

Do not add customer deposit/withdrawal, external payment, multi-currency, ML fraud blocking, notification delivery, microservices or Kubernetes before MVP acceptance gates pass.
