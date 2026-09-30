# Phase 02 — Shared Security and Request Controls

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Phase 01 and V1 database foundation.  
**References:** [`00-roadmap.md`](./00-roadmap.md), [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml), baseline auth/security requirements.

## Goal

Implement authenticated request context, JWT access-token verification, opaque refresh-session rotation, CSRF header validation, role/ownership primitives, adaptive password/PIN hashing, OTP challenge policy and local-only mailbox sender, plus approved instance-local rate limiting. Phase 03 wires complete auth/onboarding use cases.

## Dependencies and packages

Use Spring Security 6.x managed by Spring Boot 3.3.13; JJWT `0.12.6` artifacts from roadmap. Add no Redis/remote limiter. Phase-02 classes:

```text
identity/api/IdentityModuleApi.java
identity/application/AccessTokenService.java
identity/application/RefreshSessionService.java
identity/application/CsrfTokenService.java
identity/application/OtpChallengeService.java
identity/application/PinVerificationService.java
identity/domain/AuthenticatedActor.java
identity/domain/OtpPurpose.java
identity/domain/OtpChannel.java
identity/domain/OtpIssueCommand.java
identity/domain/OtpConsumeCommand.java
identity/infrastructure/security/JwtAccessTokenCodec.java
identity/infrastructure/security/RefreshTokenGenerator.java
identity/infrastructure/security/PasswordHashingService.java
identity/infrastructure/security/PinHashingService.java
identity/infrastructure/otp/OtpSender.java
identity/infrastructure/otp/LocalMailboxOtpSender.java
identity/infrastructure/otp/LocalOtpMailboxController.java
identity/infrastructure/persistence/{UserEntity,UserRoleEntity,CustomerPinEntity,OtpChallengeEntity,RefreshSessionEntity,...}.java
identity/infrastructure/persistence/{UserRepository,OtpChallengeRepository,RefreshSessionRepository,...}.java
identity/web/SecurityConfiguration.java
identity/web/BearerAuthenticationFilter.java       # only if not using Spring Resource Server filter support
shared/rate_limit/RateLimitProperties.java
shared/rate_limit/RateLimitPolicy.java
shared/rate_limit/InMemoryRateLimiter.java
shared/rate_limit/RateLimitInterceptor.java
shared/rate_limit/RateLimited.java
```

Use package name convention matching roadmap (`rate_limit` should be Java package `ratelimit` or `rateLimit`; select idiomatic `shared.ratelimit`). Do not use Python-style underscore package directories in Java source.

## Security configuration and contracts

- `SecurityFilterChain`: deny by default; permit only OpenAPI `security: []` operations, including register/send-otp/login/csrf/refresh/recovery. Protect customer/operator/account/transfer/audit/risk paths; apply exact role policy at endpoint/use-case.
- Access token: signed JWT, `sub=userId`, roles claim as approved role array, `iat`, `exp` 900 seconds. Signing key comes from protected env; enforce minimum entropy/length on startup without printing it. Do not log tokens. Validate signature, issuer/audience only if configured; avoid inventing required claims absent baseline. Clock-skew allowance is a small documented constant (recommend 30 seconds) and tested.
- Refresh: generate at least 256 bits CSPRNG; cookie name `refresh_token`, `HttpOnly`, `SameSite=Lax`, `Secure` when HTTPS, Path `/api/v1/auth`, Max-Age 604800; do not expose raw token outside Set-Cookie. Persist SHA-256 lowercase hex hash only in `refresh_sessions`.
- Rotation transaction: lock old session row by token hash; reject expired/revoked; set `revoked_at`; insert new row and `replaced_by_session_id`; return new access token and Set-Cookie. Concurrent reuse of old token succeeds at most once. Logout revokes current session. Recovery revokes all sessions in Phase 03.
- CSRF: `/auth/csrf` returns `CsrfTokenResponse` JSON. Token is random >= 128 bits, bound to refresh session or pre-auth CSRF context using server-side signed/hashed token. FE holds returned token in memory and sends `X-CSRF-Token`; no separate readable cookie. Validate header for refresh/logout before state change; constant-time compare. OpenAPI CSRF parameter schema min 16/max 256.
- Password hashing: use Spring Security `PasswordEncoder`; select Argon2id only if required BouncyCastle/native support is acceptable; recommended no extra dependency: BCrypt `BCryptPasswordEncoder` strength 12 as MVP implementation baseline, configurable and benchmarked locally. Store encoded hash only. Pin hashing uses separate encoder bean/purpose tag; do not reuse password hash or expose hash. PIN online brute-force controlled by account lockout and rate limits.
- Roles: `user_roles(user_id, role)` supports multiple roles. Principal contains user UUID and immutable role set. Never trust caller-supplied actor ID/role.
- Ownership: module use case accepts authenticated actor and resource ID; account/customer module checks ownership. Conceal resources using 404 only where contract/baseline specifies; do not change statuses.

## OTP challenge and mailbox

`OtpChallengeService.issue(OtpIssueCommand)` creates six-digit code via `SecureRandom`, stores salted adaptive hash (not raw code) and expiry 120s, max attempts 5, identifier/channel/purpose binding. `consume(OtpConsumeCommand)` runs transactional: select challenge for update by normalized identifier/purpose/channel and active state, reject absent/expired/consumed/invalidated, increment attempts for wrong code, invalidate at fifth, set consumed timestamp once on valid code. Return a typed result; HTTP layer maps exact OpenAPI stable Problem codes.

`OtpSender` is a port with `send(OtpMessage)`; `LocalMailboxOtpSender` stores code only in process memory for local/demo, keyed by challenge ID + normalized identifier, expiry <= challenge TTL, bounded entry count, overwrite/evict expired entries, no DB persistence and no logs. Shared/cloud profiles must fail startup if mailbox enabled. Mailbox endpoint:

```text
GET /__local/otp-mailbox?identifier=<phone-or-email>
```

Controller is conditionally registered only under `local` or `demo`, server binds localhost, endpoint requires a local-only development guard/header/token from local env, rate-limited, returns minimum necessary test OTP data, no public OpenAPI registration. If deployment config binds externally while mailbox is enabled, fail closed at startup. Never expose the endpoint in shared/cloud profile. E2E uses this endpoint only against a local test process; tests may call mailbox bean directly.

## Rate-limit policy (approved)

| Operation | Limit/window | Key | Applied response |
|---|---:|---|---|
| `POST /api/v1/auth/login` | 5 / 60s | Trusted client IP + normalized phone | HTTP 429 Problem `RATE_LIMITED`, `Retry-After` seconds |
| `POST /api/v1/auth/register/send-otp` | 3 / 300s | Trusted client IP + phone | Same |
| `POST /api/v1/auth/recover/initiate` | 3 / 300s | Trusted client IP + normalized identifier | Same |
| `POST /api/v1/operator/customers/send-otp` | 10 / 300s | authenticated operator ID | Same |
| `POST /api/v1/recipients/resolve` | 30 / 60s | authenticated customer ID | Same |
| `GET /api/v1/operator/customers` | 30 / 60s | authenticated operator ID | Same |

Configuration overrides these defaults. Counters are synchronized in-memory per application instance; reset on restart; not shared across replicas. Use monotonic time (`System.nanoTime`) for window accounting. Clamp `Retry-After` to >=1 second. Use trusted proxy configuration for client IP. Do not include raw identity values in metrics/logs. Bound map growth with expiry cleanup and max entries; document that an overloaded cardinality map evicts expired entries and fails closed or returns 429 rather than allocating unbounded memory. Select/test exact eviction behavior during implementation.

## Error, audit, and persistence behavior

- All 429 responses use OpenAPI `Problem`, media `application/problem+json`, code `RATE_LIMITED`, correlation ID, `Retry-After` header.
- Security failures use exact OpenAPI response statuses and stable codes. Do not add fields.
- Audit facts for auth outcomes exclude password/PIN/OTP/token, hashes and full IP unless justified; use actor/target IDs and outcome.
- JPA entities stay inside identity infrastructure. Public module API uses immutable Java records; never return JPA entities.

## Tests and acceptance

- JWT valid/expired/tampered/wrong key, roles extraction, 900s expiry, missing/weak signing key startup failure.
- Refresh rotation and concurrent replay; old token revoked, new hash persisted, raw token absent DB/log.
- Cookie flags local HTTPS/proxy profiles; CSRF token success/missing/wrong/replay cases.
- Role deny-by-default, multiple roles, no cross-module repository injection; ownership tests at module API.
- Password/PIN hash distinct, correct/incorrect, no plaintext in serialization/log capture.
- OTP leading-zero generation, hash-at-rest, purpose/channel/identifier mismatch, expiry boundary, 1–4 wrong attempts, fifth invalidates, one-time consume under concurrent confirms.
- Mailbox works only local/demo; test shared profile has no route and rejects mailbox config; endpoint bound/local guard verified.
- Rate limiter tests every approved policy, exact allowed count, next request 429, retry-after, boundary/reset, key isolation and trusted-proxy behavior.
- Testcontainers PostgreSQL covers V2 schema/entity mapping after Phase 01 V1 is stabilized.

## Implementation sequence and gate

1. Verify Phase 01 schema/config, exact OpenAPI security/CSRF/problem schemas.
2. Configure rate policies from table; no further policy choices are open.
3. Add SecurityFilterChain/principal/JWT and tests.
4. Add refresh persistence, rotation, cookie and CSRF; test concurrency with PostgreSQL.
5. Add role, owner policy and password/PIN hashing primitives.
6. Add OTP challenge entity/service and transactional consume.
7. Add local mailbox adapter/controller guards and profile isolation tests.
8. Add limiter/interceptor and endpoint annotations/registrations.
9. Run focused security and persistence suite, review cookies/headers/logging/config; update evidence.

**Questions:** ask only if actual OpenAPI/security implementation conflict appears. User has already approved rates/storage, mailbox endpoint, JWT library line, role multiplicity and CSRF token delivery.

**Verification record:** pending implementation approval.
