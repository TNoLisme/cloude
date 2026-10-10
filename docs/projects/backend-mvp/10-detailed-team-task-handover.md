# Backend MVP — Quy trình phối hợp và bàn giao

**Mục đích:** Quy định boundary, dependency, test, evidence và merge gate cho **3 BE + 1 FE/BE (4 thành viên)**. Cập nhật tiến độ realtime tại [progress.md](../../progress.md). Đây là protocol chung; plan thực thi từng người nằm trong file 11–15.

## 1. Nguồn kế hoạch

- [09 — Acceptance gaps và ownership](./09-acceptance-gap-and-team-plan.md)
- [11 — BE-A Identity/Security/Account Lifecycle](./11-be-1-identity-security-onboarding.md) (Gộp BE-1 + BE-2 Account Lifecycle)
- [13 — BE-3 Transfer/Consistency](./13-be-3-transfer-financial-consistency.md)
- [14 — BE-B Audit/Test support/Integration/Data](./14-be-4-audit-risk-integration.md) (Gộp BE-4 + BE-2 Data Support)
- [15 — FE/BE Contract/E2E/k6/Fraud Flagging/CI](./15-fe-be-contract-e2e-load-ci.md)

## 2. Owner và code boundary

| Owner | Owns | Không sửa |
|---|---|---|
| BE-A | `identity/**`, `customer/**`, `account/**`, `SecurityConfiguration`, `OnboardingController`, auth/session/OTP/PIN/recovery/rate-limit, account lock/status, seed concurrency, backend CI | transfer, audit/risk, shared error handler, disposable test support, k6, FE CI |
| BE-3 | `transfer/**`, transfer tests, rollback/concurrency/idempotency/retry | identity/account/audit/risk implementation, k6, CI |
| BE-B | `audit/**`, `shared/error/**`, disposable PostgreSQL test support, resilience harness, image scan, k6 synthetic dataset seed SQL & invariant query, evidence/matrix, OpenAPI merge | business implementation thuộc BE-A/3, FE business flow, k6 implementation |
| FE/BE | `frontend/**` integration, generated types via generator, E2E, `tests/load/banking-mvp.js`, Rule-based Anomaly/Fraud Detection UI & logic, FE CI | backend implementation, migration, unapproved contract |

Mỗi file có một owner. Reviewer không đồng nghĩa co-owner. Không sửa file ngoài boundary để unblock nhanh; mở proposal gửi đúng owner. Mọi tiến độ cập nhật vào [progress.md](../../progress.md).

## 3. Shared contract và dependency bắt buộc

| Consumer | Provider | Contract/handoff bắt buộc | Việc được làm trước khi nhận |
|---|---|---|---|
| BE-A/3 PostgreSQL integration tests | BE-B | `DisposablePostgresTestSupport.java`: disposable lifecycle, fixture/reset contract, no retained DB | Unit tests và implementation module-local |
| BE-3 transfer | BE-A | lock API/order `id ASC`, account eligibility/status/currency contract, PIN verify result, OTP consume-once semantics | Transfer unit tests và test theo contract draft đã thống nhất |
| FE/BE auth & account E2E | BE-A | endpoint, cookie/CSRF, mailbox guard, synthetic auth setup, account DTO, status matrix | OpenAPI/typecheck/build/CI |
| FE/BE transfer E2E + k6 | BE-3 | state/error matrix, idempotency/retry/reconcile behavior | Contract gate và test skeleton |
| FE/BE k6 run | BE-B | dataset synthetic, known balances, seed SQL, invariant query, DB metrics | k6 skeleton và contract gate |
| Final acceptance | BE-A/3/FE/BE | exact command/output, environment, evidence link, status | Matrix setup and review gates |

Missing handoff blocks only dependent integration proof. It does not block unrelated unit tests or module-local implementation. Report missing input as `BLOCKED`; do not invent contract/data.

Contract changes require proposal with API/schema diff, consumer impact, DB/security implications, test plan. BE-4 merges only after affected owner approval. FE regenerates types only from approved OpenAPI.

## 4. Test isolation and safety

- All PostgreSQL integration tests use disposable support owned by BE-4. No separate private container/base forks.
- Never run test/load against retained local, shared or production DB.
- Never run `docker compose down -v`, `DROP`, `TRUNCATE` or bulk deletion against retained data.
- Test cleanup may reset only disposable test schema/instance and must be explicit in support API.
- Unit tests use mocks/fakes for external systems. PostgreSQL/Testcontainers required for row-lock, transaction, uniqueness and persistence claims.
- Never commit `.env`, credentials, JWT, password, PIN, OTP, refresh token, full account number or generated secrets.
- No `PASS`/`VERIFIED` without fresh command output.

## 5. File ownership and conflict prevention

| File/path | Owner | Rule |
|---|---|---|
| `OnboardingController.java` | BE-1 | BE-2 sends proposal only |
| `shared/error/**` | BE-4 | BE-1 sends auth mapping proposal only |
| `AccountUiApiRegressionPostgresTest.java` | BE-2 | Account/operator/seed tests only |
| `TransferUiApiRegressionPostgresTest.java` | BE-3 | Transfer tests only |
| `UiApiRegressionPostgresTest.java` | Existing legacy owner until split | Freeze; no new test additions. BE-4 coordinates safe extraction, preserve all existing assertions |
| `DisposablePostgresTestSupport.java` | BE-4 | Others consume only |
| `DatabaseFailureRecoveryTest.java` | BE-4 | Harness/lifecycle only; module owners own behavior assertions |
| `contracts/openapi.yaml` | BE-4 merge | Module owners submit proposals |
| `pom.xml`, `application*.yml`, Docker files, migration numbering | BE-4 | Module owners submit proposals + impact/test evidence |
| `.github/workflows/backend-ci.yml` | BE-1 | BE-4 review |
| `.github/workflows/frontend-contract.yml` | FE/BE | BE-4 review |
| `tests/load/banking-mvp.js` | FE/BE | BE-2 dataset/query; BE-3 invariant review; BE-4 acceptance review |

If legacy regression file contains shared fixtures needed by both new files, BE-4 owns extraction of common test support. BE-2/3 do not concurrently edit legacy file.

## 6. Per-task handoff format

Each owner creates evidence under `docs/projects/backend-mvp/evidence/` using `BE1-*`, `BE2-*`, `BE3-*`, `BE4-*` or `FEBE-*` prefix.

```markdown
# Task evidence

## Status
PLANNED | IMPLEMENTED | VERIFIED | BLOCKED | NOT_ACCEPTED

## Owner and scope
- Owner:
- In scope:
- Out of scope:

## Files
- Changed:
- Contract impact: none | proposal/link
- DB impact: none | migration proposal
- Security/role impact:

## Verification
- Environment/profile/database isolation:
- Exact command:
- Expected:
- Actual:
- Result:
- Tool/version where relevant:

## Evidence
- Logs/report/screenshots (synthetic data only):

## Dependencies
- Provider + exact input:
- Blocked item, if any:

## Handoff
- Consumer:
- API/DTO/state/error details:
- Next owner/action:

## Limitations
- Known gaps:
```

## 7. Merge gate

PR is ready only when it states:

1. One owner and narrow scope.
2. Files changed; no shared-file ownership violation.
3. Exact targeted test command and actual result.
4. PostgreSQL evidence for persistence/concurrency claims.
5. OpenAPI impact: none or approved proposal.
6. DB/migration impact: none or approved forward migration proposal.
7. Role/ownership/security impact.
8. Idempotency/money invariant impact when relevant.
9. FE handoff when response/status/header/error changes.
10. Secret/log review and no unrelated formatting/generated churn.

BE-4 checks gate and evidence. Missing item means request changes or mark BLOCKED, not silent repair by another owner.

## 8. Definition of Done

P0 is verified only when evidence exists for:

- Auth/session/OTP/PIN/recovery and role/rate-limit controls.
- Account ownership, lookup, seed replay/concurrency, block behavior.
- Transfer rollback, timeout/retry, restart recovery, DB outage fail-closed.
- Overdraft, duplicate idempotency, opposite-direction locking, duplicate confirm, block-vs-confirm.
- Audit append-only/redaction and risk rule thresholds/AFTER_COMMIT isolation.
- FE Customer/Operator/Auditor E2E and unknown-outcome reconciliation.
- k6 50 VU / 20 RPS / 10 minutes with p50/p95/p99, throughput, error classes, resources, final invariant and duplicate checks.
- Backend/frontend CI, OpenAPI validation, dependency/secret/log/image scan.
- Final report with one result: `ACCEPTED` or `NOT_ACCEPTED`, plus remaining gaps.

Cloud deployment remains separate until provider, region, budget, ownership, network exposure, backup, secret injection and teardown are approved. Local acceptance does not imply cloud acceptance.
