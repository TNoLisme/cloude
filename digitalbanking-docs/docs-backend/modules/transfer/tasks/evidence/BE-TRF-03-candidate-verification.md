# BE-TRF-03 — Candidate HTTP boundary verification

> 16/11/2026. Partial implementation evidence for the Transfer HTTP boundary; **not** task completion, provider integration, end-to-end acceptance or release approval. Consumer fakes only; no real Identity/Account/Audit, no orchestrator runtime, no production activation. Reviewer: pending (API/FE/QA/Security/Backend Lead).

## 1. Status

**Implemented (candidate) / Blocked for acceptance.** The HTTP boundary is built and exercised with test-only fakes per `05` §13.4 and DEC-TRF-015/016. Acceptance ACs remain **Blocked** pending real providers, orchestrator BE-TRF-04/05, decided PII policy/staff scope, shared quota evidence and cross-module review. No AC is marked Verified.

## 2. Scope

Covered (this change):
- `TransferController` + `TransferHttpConfiguration` in `com.simulatedbank.transfercandidate.web` (outside the `banking` component-scan root; explicit registration only when orchestrator/providers/migration are ready). Thin controller: raw body + `Idempotency-Key` header → parser → one application service → outcome mapping; no `@Transactional`, no entity, no hand-built Problem (BE-CON-21–26/53).
- REST record DTOs `TransferResponse`, `TransferDetail` (`@JsonUnwrapped`), `AccountRef`, `FraudFlagSummary`; POST `consumes=application/json` (415 otherwise).
- `TransferCreateInput` wired to the endpoint and extended with safe `errors[].field` (relative JSON path / `Idempotency-Key`); 10 parser tests.
- `TransferCreateService`: service-level `TRANSFER_CREATE` + customer-session re-check, C3 deny audit, delegation to the `TransferOrchestrator` seam (lookup-before-account-read ordering documented for 04/05).
- `TransferQueryService`: read-only masked receipt; customer participant ownership (source OR destination) via `AccountOwnershipPort`; staff `TRANSACTION_READ` per `07` §3.5; `fraudFlags` only `FRAUD_READ`/`FRAUD_REVIEW`; 403-exists-out-of-scope / 404-absent.
- `SecurityDenyAudit`: best-effort C3 `recordStandalone` via `AuditEventPort` + fallback security log + metric counter; deny response unchanged on audit failure.
- Consumer ports in `transfer.application` mirroring `05` §13.4 (`AccountOwnershipPort` TRFC-AC-02, `AuditEventPort` TRFC-AU-02, `TransferReadPort`, `FraudFlagReadPort`, `TransferOrchestrator`); no classes created in other modules' `provider.api`.
- Minimal common fix: `GlobalExceptionHandler.fieldError` regex permits hyphen so header field `Idempotency-Key` survives sanitization (BE-CON-55). Field names are server-produced.
- Outcome→HTTP mapping per `07` §8.3.1: 201+Location, replay 201/422 + `Idempotent-Replayed` (body original correlation, header current), 409 conflict/in-progress (+Retry-After), 428+challenge, 422 post-T1 with reference/transferStatus/failureCode, pre-T1 404/409/422 without reference, 400/413/415 validation.

Excluded / not proven here: real session/CSRF wiring with Identity, real Account resolve/ownership and IDOR against a provider, orchestrator T1/T2 and end-to-end POST, PostgreSQL persistence for GET, shared atomic quota across instances, PII detection policy, staff server-side scope mechanism, reference format finalization (OQ-02), API/FE/QA contract review and CI contract diff.

## 3. Requirement / test IDs

`07` §8.3.1/§8.3.2 (API-TRF-01/02), §7.2.6/§7.2.10/§7.2.11, §4.1–4.3, §6.3; `03` AC-006–014, AC-018, AC-022–024, AC-033–034; `09` §5.2 boundary matrix, §7.5 (D4), §7.7 contract; CT-API-006 (missing key), CT-API-007 (replay), CT-API-008 (conflict), CT-API-010 (428), CT-API-011 (422), CT-API-015/016 (IDOR/staff), CT-API-017 (masking). DEC-TRF-003/004/012/015/016. BE-CON-21–26/53/55.

## 4. Environment

Local development, synthetic data only. Java 21 (Temurin `21.0.1`, arm64), Spring Boot `3.5.7`, Gradle `8.14.3`, PostgreSQL `16-alpine` via Testcontainers (Docker available) for the existing persistence/error pipeline tests. Profile `test`; DataSource/JPA/Flyway autoconfiguration excluded for the HTTP slice tests. No real customer data, credentials, OTP or secrets in fixtures or artifacts.

## 5. Source revision

Base commit `0a8a3ef` (branch `feature/be-trf-01-02-transfer`) plus uncommitted BE-TRF-03 candidate changes listed in `git status` (transfer application/adapter, `transfercandidate/web`, parser, one common handler line, docs). Re-run after commit to bind evidence to a fixed revision.

## 6. Procedure (repeatable)

From `digitalbanking-backend`:

```
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew check --offline --console=plain
```

Targeted subsets used during TDD:

```
./gradlew test --offline --tests "com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInputTest"
./gradlew test --offline --tests "com.simulatedbank.banking.transfer.application.TransferCreateServiceTest"
./gradlew test --offline --tests "com.simulatedbank.banking.transfer.application.TransferQueryServiceTest"
./gradlew test --offline --tests "com.simulatedbank.banking.transfer.adapter.inbound.TransferControllerHttpTest"
./gradlew test --offline --tests "com.simulatedbank.banking.transfer.adapter.inbound.TransferApiContractTest"
```

## 7. Expected

Parser rejects invalid shape/amount/destination/description/key with safe field paths and no input leakage; controller maps every outcome to the contract status/headers/body; service re-checks permission and emits C3 on deny without orchestrating; query service masks accounts, gates fraud flags by permission, returns 403/404 per policy; responses match the OpenAPI schemas; `./gradlew check` and ArchUnit consumer-only-api (with negative fixture) pass; common security tests unaffected (candidate not scanned).

## 8. Actual

`BUILD SUCCESSFUL`. Aggregate XML reports: **338 backend tests, 0 failures, 0 errors, 0 skipped** (baseline before this change was 298). Transfer scope (`banking.transfer` + `transfercandidate`): **135 tests**. New BE-TRF-03 test classes: **38 tests** —

| Test class | Tests | Result |
|---|---|---|
| `TransferCreateServiceTest` | 7 | pass |
| `TransferQueryServiceTest` | 8 | pass |
| `TransferControllerHttpTest` | 18 | pass |
| `TransferApiContractTest` | 5 | pass |
| `TransferCreateInputTest` (8 existing + 2 new field tests) | 10 | pass |

ArchUnit `TransferBoundaryTest` (consumer-only-api + negative fixture) and `ModuleBoundaryArchTest` pass; common `HttpSecurityTest`/`HttpSecurityServletTest`/error tests pass unchanged (no route conflict because the candidate controller is outside the scan root). TDD: each behavioral test was watched failing before implementation (parser field paths, service permission/C3, query scope/fraud gating, controller mapping, header-field sanitization, 415 media type).

## 9. Artifacts

- JUnit XML: `digitalbanking-backend/build/test-results/test/TEST-*.xml`; HTML report `build/reports/tests/test/index.html`; JaCoCo `build/reports/jacoco/test/html/index.html`.
- Source: `transfer/application/*` (services, ports, outcomes, values), `transfercandidate/web/*` (controller, config, DTOs), `transfer/adapter/inbound/TransferCreateInput.java`, `common/error/GlobalExceptionHandler.java` (field regex).
- Tests: `TransferCreateServiceTest`, `TransferQueryServiceTest`, `TransferControllerHttpTest`, `TransferApiContractTest`, `TransferCreateInputTest`.
- Decisions/contracts: `DECISIONS.md` DEC-TRF-016; `05. Modules contract.md` §13.4 consumer-seam note.

## 10. Limitations / Blocked (owner)

- **PII policy (BA/Security):** no production detection regex added for description/key; AC PII Blocked. Validation shape alone does not establish absence of PII.
- **Staff data scope (API/Security/Backend):** server-side scope mechanism undefined in MVP sources; staff read follows `07` §3.5 matrix only; AC scope Blocked.
- **Reference format OQ-02 (API owner):** GET validates the proposed `^TXN[0-9]{8}[A-Z0-9]{8,20}$` (matches common `ProblemResponse` and YAML); not frozen.
- **Real Identity/Account/Audit (Đạt):** `SessionAuthenticator`, `resolveDestination`/`assertOwnership`, `recordStandalone` have no production implementation; fakes prove consumer mapping only. Real session/expiry/revocation/CSRF, ownership/IDOR and PostgreSQL GET are acceptance conditions.
- **Orchestrator (BE-TRF-04/05):** `TransferOrchestrator` is a seam with no runtime implementation; end-to-end POST, replay/conflict/428/422 against real T1/T2 not proven; production endpoint not activated.
- **Shared quota (Ops/Backend/Common):** in-process limiter demonstrates single-instance behavior only; atomic shared store + multi-instance test + 503 fail-closed proof outstanding (DEC-TRF-012).
- **Contract review (API/FE/QA):** YAML omits `Cache-Control: no-store`/`Pragma: no-cache` response headers and `currency` default present in `07`; implementation follows `07` via common filters. Synchronized YAML/overview edit + CI contract diff + replay-correlation review (OI-CON-13) pending; not edited unilaterally here.
- Fake/fixture results are **not** end-to-end, PostgreSQL-invariant, D1–D5 or release evidence.

## Post-merge checks

Wire real `SessionAuthenticator`, Account `resolveDestination`/`assertOwnership` and Audit `recordStandalone`; register the orchestrator (04/05) and activate `TransferHttpConfiguration` only after the reviewed migration and provider prerequisites. Re-run the contract suite against real providers and PostgreSQL (independent connections): IDOR/cross-customer POST/GET, two users of one customer, staff permission/scope, fraud-flag gating, deny C3 + fallback, terminal replay 201/422 with correlation split, 428 no-side-effect, pre/post-T1 account mapping, masking and `no-store`. Prove shared atomic quota across instances and 503 fail-closed. Decide PII policy and staff scope, finalize reference format, synchronize YAML/overview with `07` and add CI contract diff. Record revision/command/environment/output per `09` §10 with synthetic artifacts; any missing provider/review remains Blocked.