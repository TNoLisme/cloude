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

- `TransferPolicyTest`: 2 passed.
- `RecipientControllerMvcTest`: 2 passed.
- `TransferServiceTest`: 2 passed.
- Backend compile passed.
- OpenAPI remains unchanged.

## Critical remaining gates

- PostgreSQL transaction/concurrency tests remain blocked by Testcontainers Docker API negotiation before container startup.
- Confirm-OTP implementation needs correction before acceptance: OTP locking currently uses customer UUID string instead of registered phone; OTP attempt persistence and fifth-attempt failure are not complete; expiry/failure updates currently throw inside transaction and may roll back.
- OTP dispatch currently constructs `OtpChallengeService` inside `TransferService`, and OTP challenge/transfer writes need one verified transaction boundary.
- Two-account locks currently lock source then destination; change to PostgreSQL `ORDER BY id ASC FOR UPDATE` before acceptance.
- Transfer list currently lacks cursor/time filters and returns generic counterparty labels; implement accurate privacy-safe direction/counterparty projection before acceptance.
- Debits must verify affected-row count and fail atomically.
- Phase 05 incomplete. Do not commit or push.
