# Customer — implementation report

## Canonical scope

Canonical API `07 §8.2` and OpenAPI expose only:

- `GET /api/v1/customers/me`
- `GET /api/v1/admin/customers/{customerId}`

No create/update/delete Customer REST contract, write permission, staff branch/portfolio model, or write-state transition contract exists. Production write APIs were therefore not invented. `CustomerService.create` remains an internal onboarding operation.

## Finding closure

| ID | Root Cause | Files Changed | Test Evidence | Status |
|---|---|---|---|---|
| CUS-01 | Self access trusted `customerId` from the principal without rechecking its `userId` mapping | `CustomerService.java`, `AssignedModulesIntegrationTest.java` | Forged principal with another customer ID is rejected | FIXED |
| CUS-02 | Staff lookup granted global customer access from role/permission alone although canonical scope storage is absent | `CustomerService.java`, `AssignedModulesIntegrationTest.java` | Operations user receives fail-closed `503 SERVICE_UNAVAILABLE`; customer/permission-only principals receive 403 | BLOCKED — safe fail-closed |
| CUS-03 | Provisioning validation leaked `IllegalArgumentException`; unique races became generic database errors | `CustomerService.java`, `AssignedModulesIntegrationTest.java` | Invalid PII/enum/CIF → `VALIDATION_ERROR`; concurrent duplicate provisioning has one winner and one 409 | FIXED |
| CUS-04 | Transaction rollback and unique PII/user constraints lacked integration evidence | `AssignedModulesIntegrationTest.java` | PostgreSQL rollback leaves no row; duplicate blind index does not create a partial customer | FIXED |
| CUS-05 | Potential mass assignment if clients could write profile/status fields | `AssignedModulesIntegrationTest.java` | Authenticated `POST /customers/me` is 405; persisted name/status remain unchanged | FIXED for canonical API |
| CUS-06 | Full Customer CRUD/update/status policy is not defined in canonical API/RBAC | Documentation only | Canonical contract inspection; no production endpoint exists | BLOCKED |
| CUS-07 | C3 access-denied audit adapter is owned by Thịnh and absent | Documentation only | Full context has no fake production adapter | BLOCKED |

## Implemented independent behavior

- AES-256-GCM for profile PII; HMAC-SHA256 national-ID blind index.
- Customer provisioning accepts only a real `CUSTOMER` identity and canonical KYC values.
- Self profile derives and revalidates `userId → customerId`; full name only for the owner, phone/email masked.
- Positive-ID validation and stable public business errors.
- PostgreSQL unique constraints are classified at the module boundary without exposing SQL details.
- Staff lookup is fail-closed until an authoritative scope decision contract exists.

## Test evidence

```text
./gradlew test --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.customer*' \
  --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.concurrentCustomerAndAccountProvisioningPreservesUniqueConstraints' --console=plain
BUILD SUCCESSFUL — PostgreSQL 16 Testcontainers

./gradlew test --console=plain
BUILD SUCCESSFUL — 215 tests, 0 failures, 0 errors, 0 skipped
```

## Required integrations

- Staff scope contract: given `actorUserId`, operation and `customerId`, return an authoritative allow/deny decision from canonical branch/portfolio assignments. Until defined, staff detail remains unavailable.
- Thịnh: production C3 `SECURITY_ACCESS_DENIED` adapter.
- Customer update/deactivation requires canonical REST/command contract, permission, transition rules and C2 audit semantics before implementation.
