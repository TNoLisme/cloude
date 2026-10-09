# Phase 05 Implementation Note

## Implemented

- Added `TransferPolicy` with canonical VND integer parsing, bounds `2,000..10,000,000`, and OTP threshold above `5,000,000`.
- Added `POST /recipients/resolve` with exact account-number lookup, customer-only authorization, active VND eligibility, self-recipient rejection, masked account output, and privacy-safe `RECIPIENT_NOT_AVAILABLE` response.
- Added transfer persistence repository for transfer rows, status updates, OTP challenge linkage, and participant history lookup.
- Added `TransferService` initial create/confirm flows:
  - Customer authorization.
  - Source/destination account locks.
  - Source ownership/status/currency checks.
  - PIN verification.
  - Idempotency hash and conflict detection.
  - Small-transfer balance sufficiency check, debit/credit, transfer row, and audit.
  - Large-transfer `AWAITING_OTP` row and OTP dispatch.
  - OTP confirmation, expiry/state checks, debit/credit, completion, and audit.
- Added account balance debit repository operation.
- Added transfer query service with source/destination visibility restrictions.
- Added `POST /transfers`, transfer list/detail, and confirm-OTP controller routes.
- Added focused tests for amount policy, recipient privacy, non-canonical transfer amount rejection, and insufficient-funds behavior.

## Verification

- Targeted transfer, recipient, policy, query, lifecycle, and risk tests pass.
- Full Maven suite passes: 114 tests, 0 failures/errors/skips on 2026-10-05.
- PostgreSQL/Testcontainers suite passes with Docker Desktop and Flyway V1–V5 for the phase-05 schema baseline. V6–V7 are covered by later recovery/registration evidence and current V1–V7 baseline.
- OpenAPI validation passes: 29 operations.

## Remaining scope

- Load testing, outage/restart recovery, response-loss retry, and deployment acceptance remain later-phase gates.
- Do not commit or push.