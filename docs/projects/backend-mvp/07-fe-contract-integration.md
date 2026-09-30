# Phase 07 — FE Contract Integration

**Status:** Detailed design ready for phase review; implementation requires separate approval for any code changes. FE work may proceed in parallel from OpenAPI mocks.  
**Depends on:** OpenAPI baseline; real integration incrementally depends on phases 01–06.  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## Goal

Generate FE client/types/mocks from immutable OpenAPI, enforce backend contract tests, and connect FE to real backend in staged endpoint groups while preserving session, CSRF, idempotency, privacy and stable errors.

## Toolchain decision

Use `openapi-typescript` pinned to exact version `7.6.1` for TypeScript schema types (dev dependency). Use generated `paths`/`components` types with a small handwritten fetch wrapper rather than Orval to avoid generated client runtime assumptions. For MSW fixtures, hand-authored fixture builders are acceptable but validate against generated schema types. Do not run a second generator in parallel.

FE stack is React + TypeScript + Vite. The established API base is `VITE_API_BASE_URL`; local Vite proxy maps `/api` to `http://localhost:8080`, while FE request paths remain `/api/v1/...` according to OpenAPI server mapping. Ensure Vite rewrite does not double-prefix `/api/v1`.

## Generated outputs and commands

Suggested files after inspection:

```text
frontend/package.json
frontend/openapi-ts.config.ts                 # only if generator needs config
frontend/src/api/generated/openapi.d.ts       # generated; never hand-edit
frontend/src/api/client.ts                    # typed fetch wrapper
frontend/src/api/errors.ts                    # Problem/ApiError parser
frontend/src/mocks/handlers.ts
frontend/src/mocks/fixtures.ts
frontend/vite.config.ts
contracts/openapi.yaml
```

Commands should be pinned in package scripts after confirming package manager:

```json
{
  "scripts": {
    "api:generate": "openapi-typescript ../contracts/openapi.yaml -o src/api/generated/openapi.d.ts",
    "api:check": "npm run api:generate && git diff --exit-code -- src/api/generated/openapi.d.ts"
  }
}
```

Do not assume npm if repo standard says Bun; package manager is environment/tooling and must be read from existing FE repository before implementation. Generated output should be deterministic. CI validates OpenAPI, runs generator and fails on dirty generated diff.

## Same-origin proxy and auth client

- Vite dev server proxy `/api` to backend origin; preserve path `/api/v1/...` exactly. Proxy only local development; deployed ingress serves FE and API same origin.
- No wildcard CORS with credentials. Backend CORS may remain disabled for same-origin mode; if separate origin becomes explicitly approved, exact origin allowlist, `allowCredentials=true`, and CSRF tests required.
- Access token stored only in React memory/session presentation state (Zustand per baseline), never localStorage/sessionStorage/IndexedDB.
- Refresh cookie is HttpOnly and browser-managed. Refresh/logout request must set `credentials: "include"`; CSRF token from `GET /auth/csrf` stored in memory and sent as `X-CSRF-Token`. No separate readable CSRF cookie.
- Typed API client adds bearer header for protected requests; JSON content type only for body; correlation UUID per user action; Idempotency-Key stable per money mutation/retry.
- Parse `application/problem+json` into typed error preserving status, code, detail, fieldErrors, correlationId. Do not parse `message` as authoritative field.
- 401 clears access-token/session presentation state; only refresh once where API semantics permit. 403 is forbidden, not missing resource. 404 remains endpoint-specific. 409 idempotency replays reconcile original result. 429 honors Retry-After. 503 safe retry only; transfer uses exact same body/key. No automatic retry of unsafe mutation.

## Staged integration gates

### Gate A — Contract generation

OpenAPI 3.1 validation, unique operation IDs, generated type reproducibility, examples/fixtures validated. Contract remains unchanged.

### Gate B — Identity/session

Register send OTP/register, login, CSRF/refresh/logout, password recovery, PIN lifecycle. Verify credentials and cookie behavior via browser/dev harness; use local mailbox only in local profile. Staff FE branches by role and must not treat compatibility `customerId=userId` alias as actual Customer ID.

### Gate C — Account/operator

Profile/account reads, operator lookup/OTP/create, seed, block/unblock. Verify role navigation and invalidate account cache after seed/status changes. Keep idempotency key on seed retries.

### Gate D — Transfer

Recipient resolve, PIN, small completion, large AWAITING_OTP, mailbox-driven OTP confirmation, expiry/error/retry reconciliation, history refresh. No optimistic balance update. On timeout retain exact request body and Idempotency-Key; query transfer ID/status before offering a new logical transfer.

### Gate E — History/operations

Cursor pagination, source/destination visibility, audit query and risk read role gating. Unknown enum values must not crash FE; display safe fallback without changing server semantics.

## Test matrix

- OpenAPI validation and generated diff check.
- TypeScript compile/typecheck/build; API client tests for headers, base path/proxy rewrite, credentials, CSRF, correlation and error parsing.
- Mock tests for every endpoint family: success, loading/empty, validation, 401, 403, 404, 409, 429, 500, 503, OTP pending/expired, idempotency replay.
- FE contract test verifies no raw OTP/secret persistence or logging; token not stored persistently.
- Backend contract tests against same root OpenAPI for implemented endpoints.
- Browser E2E local: registration through mailbox, login/PIN, recovery, operator seed, transfer <=5M, transfer >5M confirm, history/status. E2E credentials are generated/disposable and never committed.
- Verify same-origin proxy with path assertions; no CORS credentials wildcard.

## Implementation loop and gate

Inspect actual frontend package manager, Vite proxy setup, current generated code and existing CI. Confirm version pins against current supported tooling and security scan. After integration approval, add typegen/drift job, wrapper/errors, proxy/auth handling, then wire each gate against mocks and real backend. Do not change OpenAPI to fit frontend convenience; present exact mismatch and impact first. Record generator version and exact commands. Stop before Phase 08 pending approval.

**Verification record:** pending implementation approval.
