# Tasks: LIM-06

## Phase 1: Contract and scope

- [x] T001 Record operator clarification in DECISIONS.md, 07, OpenAPI and spec.
- [x] T002 Document account and audit dependencies in docs-common/design/05. Modules contract.md.

## Phase 2: User Story 1 — Customer view

- [x] T003 [US1] Add PostgreSQL integration tests for `limit` read query and scope/date/remaining/transaction behavior in `digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitCustomerHttpIT.java`.
- [x] T004 [US1] Implement Spring Data JPA read mapping/query in `digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/`.
- [x] T005 [US1] Implement customer read use case and DTO/MapStruct mapping in `digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/` and `adapter/in/`.

## Phase 3: User Story 2 — Authorization and HTTP

- [x] T006 [US2] Add MockMvc tests for API JSON, permissions, malformed input, ownership and unavailable provider in `digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitCustomerHttpIT.java`.
- [x] T007 [US2] Implement endpoint with `common` session/security/error and `account.api.AccountQuery` in `digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/`.
- [x] T008 [US2] Handle denied access as C3 with documented provider contract and fallback, without fabricating audit success.

## Phase 4: Verification

- [x] T009 Run PostgreSQL, HTTP and architecture tests and appropriate regression; record evidence/dependency limits in `digitalbanking-docs/docs-backend/modules/limit/E3-verification.md`.
- [x] T010 Verify contract artifacts, traceability, OpenAPI and diff; run converge and remediate any findings.

## Phase 5: Convergence

- [x] T011 [US2] Add an HTTP test proving missing account provider returns 503 without exposing a result in `digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitCustomerMissingProviderIT.java` per US2/AC3 and T006 (partial).
