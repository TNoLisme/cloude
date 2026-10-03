# Phase 03 Implementation Note

Phase 03 identity/onboarding slice is implemented but remains incomplete pending persistence and broader controller contract gates.

## Implemented

- Added registration OTP, customer registration, login, password recovery, current profile, PIN lifecycle, operator OTP/create/lookup endpoints.
- Added identity/customer/account JDBC persistence using existing V1/V2 schema. Registration and counter creation run in one transaction and create user, role, customer, unset PIN row and ACTIVE CHECKING account.
- Added account-number CSPRNG generation with up to five unique-collision retries.
- Added stable registration duplicate-constraint mapping and OTP sender-failure invalidation.
- Added PIN lockout handling through a new transaction boundary.
- Added onboarding audit events. Request correlation UUID is passed from MDC; unknown recovery identifiers are not written as synthetic target IDs.
- Added demo-only seed runner for Operator, Auditor and two Customers with active default accounts and configured PINs. Seed credentials/PINs are supplied through environment variables. Existing phone identities are not modified; email conflicts stop startup.

## Verification

- Targeted non-container Phase 02/03 suite: 33 tests, 0 failures/errors/skips in latest Surefire reports. Includes registration/recovery audit, demo-seed validation, controller validation/status/body, operator role/filter errors and PIN confirmation mismatch.
- Backend compile: passed.
- OpenAPI validation: passed, 29 operations; contract unchanged.
- `git diff --check`: passed; line-ending conversion warnings only.

## Remaining gates

- PostgreSQL/Testcontainers tests not run successfully. Docker CLI uses `desktop-linux` endpoint `npipe:////./pipe/dockerDesktopLinuxEngine`, while the Java Testcontainers process loads a user-level forced `NpipeSocketClientProviderStrategy` and receives HTTP 400 with empty Docker server metadata. Docker is running. Need remove or correct the global strategy override, then run `FoundationPostgresTest`, `OtpChallengePostgresTest`, `RefreshSessionPostgresTest`, and new registration persistence/race tests.
- Complete MockMvc contract coverage for every Phase 03 endpoint, especially recovery SMS/EMAIL, PIN routes, operator roles/filter validation and audit behavior.
- Run demo-profile startup/seed against disposable PostgreSQL with environment-provided credentials; verify rerun does not mutate existing records.
- Keep Phase 03 incomplete until persistence and API contract tests pass.

Do not commit or push.
