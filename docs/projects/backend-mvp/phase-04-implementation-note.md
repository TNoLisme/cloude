# Phase 04 Implementation Note

## Implemented

- Added `AccountJdbcRepository` for owner account reads, exact account lookup, row locking, balance credit, status updates, and masked account projections.
- Added `AccountQueryService` for customer ownership checks and exact operator lookup with role, normalization, rate limiting, audit redaction, and no wildcard search.
- Added `AccountOperatorService` for VND seed validation, account eligibility, scoped SHA-256 idempotency hash, 24-hour idempotency retention, seed ledger insertion, balance credit, and audit.
- Added `AccountStatusService` for locked ACTIVE/BLOCKED transitions, idempotent repeat calls, CLOSED rejection, reason validation, and audit.
- Added account/operator HTTP routes for account reads, seed balance, block, and unblock. Existing onboarding operator lookup route remains authoritative; duplicate route mapping removed.
- Added V3 migration for seed ledger and transfer tables/indexes.
- Added focused unit tests for role denial, seed idempotency conflict, blocked-account rejection, wildcard lookup rejection, ownership denial, idempotent block, and CLOSED rejection.
- Added Docker/Testcontainers evidence: [`evidence/docker-testcontainers.md`](./evidence/docker-testcontainers.md).

## Verification

Fresh targeted run:

```text
AccountOperatorServiceTest: 3 passed
AccountQueryServiceTest: 2 passed
AccountStatusServiceTest: 2 passed
OnboardingControllerMvcTest: 9 passed
OnboardingCookieTest: 1 passed
RateLimitEndpointMvcTest: 1 passed
OnboardingRecoveryTest: 2 passed
```

Additional checks:

- OpenAPI validation passed: 29 operations.
- Backend compile passed.
- `git diff --check` passed; only LF/CRLF conversion warnings.

## Remaining gates

- PostgreSQL/Testcontainers integration passes with Docker Desktop and PostgreSQL 16.
- Seed response replay path has targeted unit/MVC coverage; full production database concurrency remains covered by the broader PostgreSQL acceptance plan.
- Phase 05 transfer and Phase 06 history/audit/risk implementation are complete with targeted verification.
- Full load, outage/recovery, deployment, and Phase 07 frontend acceptance remain outside Phase 04.
