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

- PostgreSQL/Testcontainers persistence suite passes on Docker Desktop with JWT test secret supplied through process environment.
- Full Maven suite passes: 114 tests, 0 failures/errors/skips on 2026-10-05.

Do not commit or push.
