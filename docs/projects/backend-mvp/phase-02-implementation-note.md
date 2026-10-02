# Phase 02 Implementation Note

Phase 02 implementation remains in progress.

## Current CSRF behavior

- `GET /api/v1/auth/csrf` returns a signed stateless token.
- Token contains random 32-byte nonce plus HMAC-SHA256 signature derived from `app.security.jwt-secret`.
- `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` require `X-CSRF-Token`.
- Current validation checks signature and token shape only.
- Same token can currently be replayed because no server-side nonce state or one-time consumption exists.
- This is intentionally recorded as implementation state, not final policy approval.

## Tests added

- Issued token accepted.
- Tampered token rejected.
- Missing/invalid CSRF header rejected with HTTP 403 by filter.
- Same token accepted twice by current stateless implementation; this test documents replay behavior and must be revisited before Phase 02 completion.
- JWT signing, expiration, weak secret rejection and tampering.
- In-memory limiter count, reset, key isolation and retry-after.
- Refresh/logout unit contract tests verify CSRF admission, 401 before rotation/revoke, rotated cookie attributes, access-token response and logout cookie clearing.
- Local mailbox guard unit and MVC tests pass under `local` profile. Shared/default profile route remains absent because controller is profile-scoped.
- Rate-limit primitive and `429 Problem` mapping unit tests pass.

## Verification status 2026-10-02

- `mvn -q -DskipTests compile`: passed.
- Targeted non-container Phase 01/02 suite: passed, 20 tests; includes session cookie/Problem, mailbox guard/configuration, rate-limit primitives and global error mapping.
- `git diff --check`: passed. Git reports only line-ending conversion warnings for existing working-copy files.
- `FoundationPostgresTest`: blocked before test execution because Testcontainers cannot find/negotiate a valid Docker environment. Docker CLI reports Docker Desktop 29.4.1, while Testcontainers' named-pipe client returns HTTP 400 with empty server metadata. No application workaround added.
- `RateLimitEndpointMvcTest`: remains deferred. `/auth/login` has no controller; request resolves to static resource handler and global handler returns 500 for `NoResourceFoundException`, so it cannot validate the rate limiter. No fake business endpoint added.
- OpenAPI validation remains unchanged; no contract edits made.
- CSRF replay remains stateless and accepted, as requested. No nonce store or TTL added.

## Remaining implementation

- Add real endpoint rate-limit integration tests with Phase 03 auth/operator/recipient controllers.
- Run OpenAPI validation/operation-ID check through existing `be/scripts/validate-openapi.py` as part of the phase gate.
- Resolve Testcontainers Docker Java-client compatibility, then run PostgreSQL OTP and refresh concurrency suites.

Do not mark Phase 02 complete until remaining contract/tests pass. Do not decide final CSRF replay policy from this note alone.
