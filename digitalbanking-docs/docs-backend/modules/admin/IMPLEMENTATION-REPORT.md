# Admin — implementation report

## Requirement status

| Requirement | State | Implementation / evidence |
|---|---|---|
| Approval persistence/lifecycle | VERIFIED | PENDING requests support reject/apply; expired requests cannot be decided; pending uniqueness is enforced. |
| Application authorization | VERIFIED | Create, decide, list, and detail enforce staff role, canonical permissions, checker privilege, and read scope in `ApprovalService`. |
| Maker-Checker | VERIFIED | Maker self-decision is rejected before challenge consumption. |
| Step-up binding | VERIFIED | Create/decision challenges bind actor, purpose, action, target, and canonical payload hash; consumption is single-use. |
| Query/filter/page/sort | VERIFIED | Request type, status, maker, checker, time range, page, size, and canonical sort are parameterized and validated. List summaries omit payload/result. |
| Concurrent decision | VERIFIED | PostgreSQL `FOR UPDATE NOWAIT` plus conditional update allows one winner; competing decision gets `APPROVAL_ALREADY_DECIDED`. |
| Role change | PARTIAL | Identity role mutation and target-session revocation are implemented. Production execution is BLOCKED until Thịnh provides required C2 audit persistence. |
| Limit change | BLOCKED | Vũ's canonical limit mutation port/adapter is absent. Requests fail closed before step-up consumption; no foreign public port or fake adapter was created. |
| Audit | BLOCKED | `ApprovalAuditSink` is an admin-owned outbound SPI. C2 is required in-transaction; C3 standalone failures cannot alter approval state. Production adapter is absent. |

## Test evidence

```text
./gradlew test --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.approval*' --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.maker*'
BUILD SUCCESSFUL — PostgreSQL 16 Testcontainers

./gradlew test
BUILD SUCCESSFUL
```

Covered: filters/pagination, summary payload non-exposure, unauthorized reads, expiry before challenge consumption, missing checker privilege, maker self-decision, two-checker concurrency, session revocation, C3 audit failure isolation, and fail-closed limit dependency.

## Handoff / known issues

- Vũ: publish the canonical limit mutation signature, result type, idempotency key, and transaction semantics.
- Thịnh: adapt canonical C2/C3 audit operations to `ApprovalAuditSink`; C2 must join the approval transaction.
- Policy thresholds still marked OPEN in canonical docs were not hard-coded.
