# Tasks: Controlled limit changes LIM-07/08

## Phase 1: Setup

- [x] T001 Resolve four clarifications and quality checklist in spec.md/checklists/requirements.md; generate plan.md, research.md, data-model.md, contracts/limit-changes.md and quickstart.md.
- [x] T002 Run scoped baseline in digitalbanking-backend/ (108 limit tests, zero failures/skips); verify existing .gitignore/.dockerignore cover Java/build/secrets.

## Phase 2: Foundation

- [x] T003 Define internal LimitChanges contract and Proposal record at digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/LimitChanges.java; trusted positive scope/maker/approval IDs, VND, caller transaction and deferred caller responsibilities documented.
- [x] T004 Add PostgreSQL red regression for missing provider in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java; use real application role, migrations, caller transactions and existing AuditRecorder.

## Phase 3: US1 Prepare proposals

Independent test: prepare pending version; active config and usage byte-for-byte unchanged, transaction rollback removes proposal.

- [x] T005 [US1] Test valid/customer/account proposals, duplicate approval, concurrent distinct versions, validation and rollback in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java; amounts '> 0', 'perTransactionLimit <= dailyLimit', 'VND', no more than 17 integer/2 fractional digits; positive IDs, no future effectiveFrom.
- [x] T006 [US1] Implement explicit policy and preparation in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitChangeService.java and scoped lock/version queries in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java; missing/incomplete/invalid maxima refuse changes, no numeric default, no usage writes, no admin data writes.
- [x] T007 [US1] Add V5__enable_limit_configuration_decisions.sql in digitalbanking-backend/src/main/resources/db/migration/ for unique nonnull approval link and lifecycle-only UPDATE grants; update expected additional migration count in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitPersistenceIT.java without changing V3/V4.

## Phase 4: US2 Decide proposals

Independent test: approve/reject/competition/rollback on PostgreSQL including first activation; all usage and immutable version data preserved.

- [x] T008 [US2] Test approve/reject, self/unknown/repeated decision, lowered-limit usage preservation and current policy revalidation in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java and LimitChangePolicyTest.java; rejection keeps 'approved_by/approved_at NULL' and works without policy.
- [x] T009 [US2] Implement serialized conditional lifecycle updates and immutable history in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitChangeService.java and adapter/out/LimitJdbcRepository.java; physically active writable READ COMMITTED MANDATORY transaction, supersede/activate at decision time, future refusal and no partial writes.
- [x] T010 [US2] Coordinate enforcement by acquiring same customer lock before existing usage locks in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java; implement D4 under that lock without usage writes, considering absent/expired account configs and customer used amount OR completed count.
- [x] T011 [US2] Verify concurrent first decisions, two proposals same scope, E2 completion versus D4 activation, next unused business day, lowered limits and pre-activation T2 fail-closed behavior in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java; two worker threads maximum.
- [x] T012 [US2] Verify real AuditRecorder success/failure (including caught failure), technical rollback, transaction/read-only/isolation/afterCommit guard and lifecycle column privileges in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java.

## Phase 5: Verification and handoff

- [x] T013 Run scoped limit.* and ModuleBoundaryArchTest, then build -x test in digitalbanking-backend/; inspect XML totals, failures/errors/skips and git diff --check. Do not run unrelated full regression.
- [x] T014 Document policy configuration and evidence/deferred integrations in digitalbanking-docs/docs-backend/modules/limit/README.md, E4-verification.md, modules/_overview/06-limit.md and deployment/configuration.md.
- [x] T015 Run independent subagent speckit-converge against this spec/plan/tasks; implement every appended task and repeat until zero findings, then record outcome in E4-verification.md.

## Dependencies and execution

T001–004 → US1 T005–007 → US2 T008–012 → T013–015. Tests precede implementation; same-file writes sequential. US1 independently verifies safe preparation; US2 requires pending provider. Policy unit tests and lifecycle integration cases can be authored independently, but execute test/build jobs sequentially with --max-workers=2 to protect local machine. No parallel production implementation agents needed.

## Deferred acceptance

LIM-09/10 production endpoints, admin approval storage/workflow, identity step-up/auth, real account/customer ownership/FKs, actual GET limits and transfer integration are deferred by explicit clarification. Provider harness never closes their acceptance. No generic approval platform or scheduler/backfill is created. D5 HTTP mapping remains a future integration decision.
