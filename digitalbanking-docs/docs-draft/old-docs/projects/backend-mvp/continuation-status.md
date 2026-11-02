# Phase 02/03/04/05 Continuation Status

## Current implementation

- Docker/Testcontainers evidence recorded at `docs/projects/backend-mvp/evidence/docker-testcontainers.md`.
- Phase 04 account/operator slice implemented partially:
  - account reads
  - operator seed balance
  - seed ledger migration
  - idempotency storage
  - block/unblock
  - exact operator lookup through existing onboarding route
  - unit tests
- Phase 05 started:
  - transfer amount policy
  - recipient resolution
  - privacy tests
- Phase 05 transfer state machine still missing.

## Fresh verification

- Backend compile passed.
- OpenAPI validation passed, 29 operations.
- `git diff --check` passed; only LF/CRLF conversion warnings.
- Targeted Phase 04/regression suite passed:
  - AccountOperatorServiceTest: 3
  - AccountQueryServiceTest: 2
  - AccountStatusServiceTest: 2
  - OnboardingControllerMvcTest: 9
  - OnboardingCookieTest: 1
  - RateLimitEndpointMvcTest: 1
  - OnboardingRecoveryTest: 2
- RecipientControllerMvcTest: 2 passed.
- TransferPolicyTest: 2 passed.
- PostgreSQL Testcontainers tests remain blocked before container startup:
  - FoundationPostgresTest
  - OtpChallengePostgresTest
  - RefreshSessionPostgresTest

## Known gaps

- Unknown recovery audit events are now omitted because V1 `audit_events.target_id` is NOT NULL. This avoids invalid inserts; product audit policy still needs explicit review.
- Seed response replay should be verified against PostgreSQL and full HTTP contract.
- Full transfer create/confirm/list/detail, audit query, risk flags, FE integration, E2E, load, and deployment remain incomplete.
- No commit or push performed.
