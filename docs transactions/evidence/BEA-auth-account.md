# BE-A Evidence: Authentication, Security & Account Lifecycle

## Status
VERIFIED

## Owner and scope
- Owner: BE-A (Identity, Security, Onboarding, Account Lifecycle, Operator Controls)
- In scope:
  - Token Family Rotation, Grace Period & Recursive CTE Revocation
  - Brute-force PIN/OTP Lockout & Success Reset
  - Sensitive Log & Timing Attack Defense (Anti-User Enumeration)
  - Duplicate Deposit Prevention & Pessimistic Locking (`SELECT ... FOR UPDATE`)
  - Idempotency-Key Validation & Replay Semantics
  - IDOR & Object-Level Authorization on Accounts
  - Account Lifecycle Transitions (`ACTIVE` <-> `BLOCKED`, `CLOSED` rejection)
  - Duplicate Registration Prevention (`uq_users_phone`, `uq_users_email_normalized`, case/whitespace normalization)
  - Backend CI workflow (`.github/workflows/backend-ci.yml`)
- Out of scope:
  - Transfers & Financial Ledger Consistency (BE-3)
  - Disposable Testcontainer base & Toxiproxy Resilience Harness (BE-B)
  - Frontend UI & k6 load testing (FE/BE)

## Files
- Changed:
  - `be/src/main/java/com/bank/simulator/identity/application/OnboardingService.java`
  - `be/src/main/java/com/bank/simulator/identity/infrastructure/security/RefreshSessionRepository.java`
  - `be/src/main/java/com/bank/simulator/identity/infrastructure/security/RefreshSessionService.java`
  - `be/src/test/java/com/bank/simulator/identity/infrastructure/security/RefreshSessionPostgresTest.java`
  - `be/src/test/java/com/bank/simulator/identity/web/SessionControllerTest.java`
- Created:
  - `be/src/main/java/com/bank/simulator/identity/infrastructure/security/RefreshSessionReuseException.java`
  - `be/src/test/java/com/bank/simulator/AccountUiApiRegressionPostgresTest.java`
  - `be/src/test/java/com/bank/simulator/identity/OnboardingSecurityPostgresTest.java`
  - `.github/workflows/backend-ci.yml`
  - `docs/projects/backend-mvp/evidence/BEA-auth-account.md`
- Contract impact: none (100% compliant with `contracts/openapi.yaml`, 31 operations verified)
- DB impact: none (Flyway migrations V1–V7 intact)
- Security/role impact:
  - Enforced 3 roles + ADMIN (`CUSTOMER`, `OPERATOR`, `AUDITOR`, `ADMIN`)
  - Cross-role privilege escalation prevented (403 FORBIDDEN)
  - Session revocation on password recovery confirm

## Verification
- Environment/profile/database isolation:
  - OS: Linux x86_64, JDK 21.0.11-ea, Maven 3.9+
  - Docker 29.7.2, Testcontainers 1.21.4
  - PostgreSQL 16 (image `postgres:16-alpine`), isolated disposable container via `DisposablePostgresTestSupport.java`
- Exact commands:
  ```bash
  cd be && mvn clean test
  python3 be/scripts/validate-openapi.py
  ```
- Expected: All unit and PostgreSQL integration tests pass (0 failures, 0 errors, 0 skipped), OpenAPI valid.
- Actual:
  - Maven Surefire: **196 tests run, 0 failures, 0 errors, 0 skipped across 63 test classes** (Execution time: 56.3s).
  - OpenAPI Spec Validator: **OpenAPI validation passed: 31 operations**.
- Result: **PASS**

## Evidence

### 1. Duplicate Deposit Prevention & Pessimistic Locking
| Test Name | File | Assertion & DB Verification | Result |
| :--- | :--- | :--- | :---: |
| `concurrentDepositsSerializeViaPessimisticLock` | `AccountUiApiRegressionPostgresTest` | 5 threads deposit concurrently to same account. `SELECT balance` = 5,000,000 VND, `account_seed_records` = 5. | PASS |
| `concurrentSameIdempotencyKeyAppliesCreditOnlyOnce` | `AccountUiApiRegressionPostgresTest` | 4 threads send same key simultaneously. Only 1 credit applied, balance = 2,000,000 VND, `account_seed_records` = 1. | PASS |
| `idempotencyKeyWithDifferentPayloadIsRejected` | `AccountUiApiRegressionPostgresTest` | Reusing key with tampered amount or reference throws 409 `IDEMPOTENCY_KEY_REUSED`. | PASS |
| `sequentialReplayReturnsCachedResultWithoutCrediting` | `AccountUiApiRegressionPostgresTest` | Replay with identical payload returns `replayed=true`, balance unchanged. | PASS |

### 2. IDOR Prevention & Object-Level Authorization
| Test Name | File | Assertion & DB Verification | Result |
| :--- | :--- | :--- | :---: |
| `customerListingAccountsNeverLeaksOtherCustomerAccounts` | `AccountUiApiRegressionPostgresTest` | Customer A only receives their own accounts ordered by `opened_at DESC, id DESC`. | PASS |
| `customerAccessingOtherCustomerAccountThrowsForbidden` | `AccountUiApiRegressionPostgresTest` | Customer A accessing Customer B accountId throws 403 `FORBIDDEN`. Random ID throws 404 `ACCOUNT_NOT_FOUND`. | PASS |
| `customerRoleCannotExecuteOperatorEndpoints` | `AccountUiApiRegressionPostgresTest` | Customer/Auditor attempting seed, block, or customer lookup throws 403 `FORBIDDEN`. | PASS |

### 3. Duplicate Registration Prevention
| Test Name | File | Assertion & DB Verification | Result |
| :--- | :--- | :--- | :---: |
| `duplicatePhoneRegistrationIsRejected` | `OnboardingSecurityPostgresTest` | Second registration with same phone throws 409 `PHONE_ALREADY_REGISTERED`. | PASS |
| `duplicateEmailWithCaseAndWhitespaceIsNormalizedAndRejected` | `OnboardingSecurityPostgresTest` | Email with uppercase and whitespace normalized to lowercase; second registration throws 409 `EMAIL_ALREADY_REGISTERED`. | PASS |
| `databaseDirectlyEnforcesUniqueConstraints` | `OnboardingSecurityPostgresTest` | Postgres raises `uq_users_phone` and `uq_users_email_normalized`. | PASS |
| `databaseCheckConstraintRejectsNonNormalizedEmail` | `OnboardingSecurityPostgresTest` | Postgres check constraint `ck_users_email_normalized` rejects untrimmed/non-lowercase email. | PASS |
| `concurrentRegistrationWithSamePhoneHandlesUniqueConstraintSafely` | `OnboardingSecurityPostgresTest` | 2 concurrent registrations with same phone: 1 winner (201), 1 conflict (409). DB ground truth has exactly 1 user/customer/account. | PASS |

### 4. Account Lifecycle & Operator Controls
| Test Name | File | Assertion & DB Verification | Result |
| :--- | :--- | :--- | :---: |
| `operatorCanBlockAndUnblockAccount` | `AccountUiApiRegressionPostgresTest` | State transition `ACTIVE` -> `BLOCKED` -> `ACTIVE` persists to DB and writes 2 `ACCOUNT_STATUS_CHANGED` audit events. | PASS |
| `blockedAccountRejectsDeposit` | `AccountUiApiRegressionPostgresTest` | Operator seeding to `BLOCKED` account rejected with 409 `ACCOUNT_NOT_ELIGIBLE`. Balance unchanged. | PASS |
| `closedAccountCannotChangeStatus` | `AccountUiApiRegressionPostgresTest` | Reopening or modifying `CLOSED` account rejected with 409 `ACCOUNT_NOT_ELIGIBLE`. | PASS |
| `statusChangeValidatesReason` | `AccountUiApiRegressionPostgresTest` | Reason blank, null, or >500 chars rejected with 400 `VALIDATION_ERROR`. | PASS |

### 5. Account Takeover (ATO) Prevention & Session Management
| Test Name | File | Assertion & DB Verification | Result |
| :--- | :--- | :--- | :---: |
| `passwordResetRevokesAllActiveRefreshSessionsInDatabase` | `OnboardingSecurityPostgresTest` | Recovery confirm revokes all active refresh sessions in PostgreSQL. Rotations with old tokens rejected. Password hash updated. | PASS |
| `pinLockoutAndResetPersistsToPostgreSql` | `OnboardingSecurityPostgresTest` | 5 failed attempts trigger 15-minute lockout in DB. Correct PIN during lockout rejected. After lockout expires, correct PIN resets `failed_attempts = 0`. | PASS |
| `loginProtectionAgainstTimingAttackAndEnumeration` | `OnboardingSecurityPostgresTest` | Wrong password, non-existent phone, and inactive user return identical 401 `CREDENTIALS_INVALID`. | PASS |
| `recoveryInitiateDoesNotLeakUserExistence` | `OnboardingSecurityPostgresTest` | Non-existent user returns generic message. No token/challenge generated in DB. | PASS |
| `reuseAfterGracePeriodRevokesTokenFamilyAndCommitsToPostgres` | `RefreshSessionPostgresTest` | Reusing old refresh token after 10s grace period revokes entire token family in DB via recursive CTE. | PASS |
| `reuseWithinGracePeriodRejectsWithoutRevokingNewToken` | `RefreshSessionPostgresTest` | Reusing old refresh token within 10s grace period rejects token without revoking active replacement. | PASS |

## Dependencies
- Provider: BE-B provides `DisposablePostgresTestSupport.java` for test isolation.
- Blocked item: None.

## Handoff
- Consumer:
  - BE-3: Transfer module consumes `AccountJdbcRepository.lockPair`, PIN verify contract (`PinCredentialService.verify`), and status checks (`ACTIVE` only).
  - FE/BE: Frontend consumes `/auth/*`, `/accounts/*`, and `/operator/*` contracts.
- Next owner/action: Handover to BE-B (Integration Lead) for review and acceptance.

## Limitations
- None.
