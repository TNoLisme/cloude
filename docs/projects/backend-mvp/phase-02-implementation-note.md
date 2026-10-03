# Phase 02 Implementation Note

Phase 02 application foundations are implemented, but PostgreSQL/Testcontainers persistence verification remains incomplete.

## Implemented and verified

- Refresh cookie uses remaining session lifetime, `Path=/api/v1/auth`, `HttpOnly`, `SameSite=Lax`, and `Secure` only for HTTPS.
- Refresh/logout and CSRF failures return OpenAPI `Problem` responses.
- Local OTP mailbox is restricted to `local`/`demo`, guarded, and bound to localhost by profile configuration.
- Rate-limit handler returns Problem `RATE_LIMITED` and `Retry-After`. MVC test reaches actual `/auth/login` endpoint.
- OTP persistence includes latest-active challenge lookup and sender-failure invalidation.
- Stateless CSRF replay behavior remains unchanged.
- OpenAPI contract unchanged and validates with 29 operations.

## Verification

- Targeted non-container Phase 02/03 suite: 33 tests, 0 failures/errors/skips in latest Surefire reports.
- Backend compile: passed.
- OpenAPI validation: passed, 29 operations.
- `git diff --check`: passed; line-ending conversion warnings only.

## Remaining gate

- `FoundationPostgresTest`, `OtpChallengePostgresTest`, and `RefreshSessionPostgresTest` still cannot start containers through Java Testcontainers. Docker CLI connects and runs containers, but Testcontainers uses a user-level forced `NpipeSocketClientProviderStrategy` and receives HTTP 400 with empty server metadata. Correct the local Testcontainers strategy/endpoint and rerun the tests. No application workaround added.
- Do not mark Phase 02 complete until all PostgreSQL persistence tests pass.

Do not commit or push.
