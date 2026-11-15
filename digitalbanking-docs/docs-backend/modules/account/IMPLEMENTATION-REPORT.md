# Account — implementation report

## Canonical scope

Canonical API `07 §8.2` and OpenAPI expose only account list/detail reads. They do not define create/update/lock/close REST operations or corresponding write permissions. Internal creation remains an onboarding operation. Status mutation is not exposed until canonical authorization, transitions and C2 audit integration are defined.

## Finding closure

| ID | Root Cause | Files Changed | Test Evidence | Status |
|---|---|---|---|---|
| ACC-01 | Self list/detail trusted principal `customerId` without rechecking the identity mapping | `AccountService.java`, `AssignedModulesIntegrationTest.java` | Forged `userId/customerId` principal is denied for list and detail | FIXED |
| ACC-02 | Staff list/detail could read every customer with `ACCOUNT_READ` despite absent staff scope model | `AccountService.java`, `AssignedModulesIntegrationTest.java` | Valid Operations staff receives fail-closed `503`; customer/invalid staff principals remain 403 | BLOCKED — safe fail-closed |
| ACC-03 | Invalid provisioning and duplicate account races did not produce stable business errors | `AccountService.java`, `AssignedModulesIntegrationTest.java` | Invalid type/number/opening balance → 400; concurrent duplicate number has one winner and one 409 | FIXED |
| ACC-04 | Per-item `asOf` used database `updated_at`, inconsistent with response snapshot time | `AccountService.java`, `AssignedModulesIntegrationTest.java` | Every returned item has the same `asOf` as `AccountsResponse.asOf` | FIXED |
| ACC-05 | Creation rollback, customer status and repository constraints lacked real-PostgreSQL evidence | `AssignedModulesIntegrationTest.java` | Rollback leaves no account; INACTIVE customer cannot open one; unique blind index enforced | FIXED |
| ACC-06 | Client could attempt status/balance mass assignment if an unmapped write were accepted | `AssignedModulesIntegrationTest.java` | Authenticated `POST /accounts/{id}` is 405; balances remain zero | FIXED for canonical API |
| ACC-07 | Balance mutation could commit independently of Ledger | `AccountService.java` | Debit/credit in a real transaction return `SERVICE_UNAVAILABLE`; balances remain unchanged | BLOCKED — safe fail-closed |
| ACC-08 | Lock/close transition permissions, transition matrix and required C2 audit adapter are not canonicalized | Documentation only | No write endpoint or production adapter exists | BLOCKED |

## Implemented independent behavior

- Account number AES-256-GCM encryption, HMAC-SHA256 unique lookup and last-four masking.
- Zero-only opening balance; owner comes from trusted internal onboarding input, never a public request DTO.
- Self list/detail revalidates `userId → customerId` and enforces ownership at the application layer.
- Canonical read statuses `ACTIVE`, `LOCKED`, `CLOSED`; invalid filters return 400.
- One-statement account detail snapshot and consistent `asOf` values.
- Ordered PostgreSQL row locking remains available to the transfer/ledger integration transaction.
- Debit/credit remain fail-closed; no fake Ledger implementation exists.

## Test evidence

```text
./gradlew test --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.account*' \
  --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.concurrentCustomerAndAccountProvisioningPreservesUniqueConstraints' --console=plain
BUILD SUCCESSFUL — PostgreSQL 16 Testcontainers

./gradlew test --tests 'com.simulatedbank.banking.migration.PostgreSqlFlywayMigrationIntegrationTest' --console=plain
BUILD SUCCESSFUL — V1 → V2, PostgreSQL 16, business modules enabled

./gradlew test --console=plain
BUILD SUCCESSFUL — 215 tests, 0 failures, 0 errors, 0 skipped
```

## Required integrations

- Phương/Ledger: define one atomic posting boundary that owns ledger entry insertion and account balance changes in the same PostgreSQL transaction. Existing `AccountBalancePort` methods remain unavailable for standalone mutation.
- Staff scope: authoritative actor-to-customer scope decision contract.
- Thịnh: C2 `ACCOUNT_STATUS_CHANGED` transaction integration and C3 denial audit.
- Account lock/close APIs require approved permissions and transition rules; they were not inferred from schema status values alone.
- V2 runs on PostgreSQL 16 after V1. A migration-version collision with newer remote work still requires coordination before merge.
