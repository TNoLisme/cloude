# Backend MVP Acceptance Gaps and Team Plan

**Ngày cập nhật:** 2026-10-09  
**Trạng thái:** Team planning — dùng để chia task P0 sau khi FE đã tích hợp API thật.  
**Phạm vi:** Acceptance evidence cho Backend MVP; không mở rộng business scope, không thêm ML, không đổi OpenAPI nếu chưa có approval.

## 1. Mục tiêu

Hoàn thiện bằng chứng nghiệm thu còn thiếu của Digital Banking Simulator sau khi các vertical slice BE và FE đã triển khai:

- Failure and recovery.
- Concurrency and financial invariants.
- Idempotency under retry/concurrent duplicate.
- Load test với k6.
- Security, observability và deployment evidence.
- Bàn giao rõ ràng giữa BE và FE.

FE đã nối API thật và đã kiểm tra browser cho Customer, Operator và Auditor. FE không còn là blocker chính cho P0; FE tiếp tục hỗ trợ E2E, chạy kịch bản và thu thập evidence.

## 2. Quyết định phạm vi hiện tại

### 2.1 Cloud deployment

**Quyết định làm việc:** Ưu tiên hoàn thiện evidence local/container trước khi chọn và provision cloud.

Lý do:

- Local acceptance chưa hoàn tất failure, concurrency và load test.
- Deploy sớm sẽ thêm biến số về network, TLS, secret, database và chi phí.
- Kết quả local tạo baseline để so sánh khi deploy.
- Không thực hiện paid/external provisioning khi chưa chốt provider, region, budget và teardown plan.

**Cloud decision gate sau local acceptance:** Team chọn một target duy nhất trong AWS ECS/EC2, GCP Cloud Run hoặc Render/Railway. Không triển khai đồng thời nhiều provider.

Trước khi provision cần chốt:

- Provider và region.
- Account/project owner.
- Budget cap và budget alert.
- Public/private network exposure.
- Managed PostgreSQL và backup policy.
- TLS/HTTPS ingress.
- Secret injection.
- Rollback và teardown procedure.

### 2.2 Fraud flagging

**Quyết định MVP:** Giữ rule-based detection. Không thêm ML model trong MVP.

Rules hiện tại:

- `LARGE_TRANSFER v1`: amount `> 5,000,000` VND.
- `HIGH_FREQUENCY v1`: hơn 5 completed outgoing transfers trong rolling window 10 phút.

Risk flag:

- Chạy sau transfer commit.
- Không block hoặc rollback transfer.
- Read-only trong MVP.
- Có `ruleId`, `ruleVersion`, `transferId`, `reason`, `detectedAt`.
- Không thêm review workflow, ML score hoặc `REVIEWED` state.

ML chỉ xem xét post-MVP khi có dataset, tiêu chí đánh giá, privacy design, chi phí và yêu cầu nghiệp vụ được approval riêng.

## 3. Trạng thái hiện tại

| Hạng mục | Backend | FE | Evidence hiện có | Gap còn lại |
|---|---|---|---|---|
| Identity/session/PIN/recovery | Implemented | Integrated | Backend package và API smoke; browser flows | Full final security review |
| Customer accounts | Implemented | Integrated | Account/operator tests và browser QA | Full concurrency/DB acceptance evidence |
| Operator lookup/seed/block | Implemented | Verified | MVC, API smoke, desktop/tablet/mobile QA | Include in final E2E report |
| Internal transfer | Implemented | Integrated | Threshold, OTP, replay, history/status tests | Fault injection, concurrent race, restart retry |
| History/status | Implemented | Integrated | PostgreSQL regression và browser QA | Include in final acceptance matrix |
| Audit trail | Implemented | Integrated | Audit API/smoke/browser evidence | Log redaction and append-only review |
| Risk flags | Implemented, rule-based | Integrated | Large/frequency smoke evidence | Failure isolation and duplicate flag evidence |
| OpenAPI | 31 operations | Generated types match | Validator and `api:check` pass per latest evidence | Contract gate rerun after final changes |
| Backend tests | 154 tests pass per latest recorded evidence | 14 tests pass per latest recorded evidence | Phase/frontend continuation docs | Fresh final run after P0 changes |
| Docker/Testcontainers | Verified | Uses local backend | PostgreSQL 16/Testcontainers evidence | Use isolated disposable DB for new tests |
| Load test | Not complete | FE can support scenario setup | No final k6 report | 50 VU, 20 RPS, 10 minutes |
| Failure/recovery | Partial implementation coverage | UI unknown-outcome path exists | Unit/regression evidence only | Deterministic rollback, timeout, restart, DB outage |
| Cloud deployment | Not started | Not started | Provider not selected | Select provider after local gate |

## 4. P0 acceptance work

### P0.1 Failure and recovery

**Owner:** BE-4 owns disposable-environment harness and evidence; BE-3 owns transfer fail-closed behavior; BE-1 owns OTP dispatch failure semantics; FE/BE verifies user-visible unknown outcome.

Required scenarios:

1. **Debit attempt then credit failure**
   - Inject deterministic failure after debit statement and before commit.
   - Assert source balance unchanged.
   - Assert destination balance unchanged.
   - Assert transfer/idempotency/audit transaction effects rolled back.

2. **Timeout after commit**
   - Commit transfer, delay or suppress response in isolated test profile.
   - Retry exact request with same actor, payload and `Idempotency-Key`.
   - Assert original logical result is returned.
   - Assert exactly one debit and one credit.

3. **Application restart**
   - Execute request against disposable PostgreSQL.
   - Restart backend after commit or during controlled transaction boundary.
   - Retry with same key.
   - Assert persisted transfer, balances and idempotency result remain correct.

4. **Database unavailable**
   - Stop only disposable test database/dependency.
   - Assert health returns `503` with safe `{status,timestamp}` shape.
   - Assert transfer does not return false success.
   - Restore dependency and verify safe recovery.

5. **OTP dispatch failure**
   - Force sender failure after `AWAITING_OTP` commit.
   - Assert initial response is `503 SERVICE_UNAVAILABLE`.
   - Assert only `AWAITING_OTP` can become `FAILED` with `OTP_DISPATCH_FAILED`.
   - Assert terminal states are not overwritten by compensation.

### P0.2 Concurrency and financial invariants

**Owner:** BE-3 owns transfer concurrency; BE-2 owns account/seed concurrency; BE-1 owns auth/session/registration concurrency. BE-4 verifies shared DB harness and consolidates evidence.

Required scenarios:

1. Two concurrent debits exceed source balance.
2. Same transfer idempotency key submitted concurrently.
3. Opposite-direction transfers A→B and B→A.
4. Duplicate OTP confirmation submitted concurrently.
5. Account block races with transfer confirmation.
- Concurrent duplicate seed requests (BE-2).

Assertions:

- Balance never becomes negative.
- Debit and credit amounts match exactly.
- One logical idempotent mutation only.
- Lock order is PostgreSQL `id ASC`.
- No persistent deadlock.
- Rejected operations do not mutate balance.
- Terminal transfer states do not move backward.

Use PostgreSQL/Testcontainers. Mock-only tests do not prove row-lock behavior.

### P0.3 Load test with k6

**Execution owner:** FE/BE writes and runs `tests/load/banking-mvp.js`; BE-2 owns seed SQL, synthetic balances, invariant query and DB metrics; BE-3 reviews financial assertions; BE-4 verifies report and acceptance status.

Baseline workload:

- 50 virtual users.
- 20 RPS steady target.
- 10-minute duration.
- Disposable PostgreSQL and synthetic identities/accounts.
- Main scenario uses transfers `<= 5,000,000` VND so OTP dispatch does not dominate baseline latency.
- Separate step-up scenario may test `> 5,000,000` VND with local mailbox.

Record:

- k6 version and script path.
- Commit/version.
- Machine and Docker resource shape.
- PostgreSQL version and configuration.
- VU, RPS, duration and request mix.
- p50, p95, p99 latency.
- Throughput.
- Expected business 4xx versus unexpected 5xx.
- CPU, memory, DB connections and lock waits.
- Financial invariant result.
- Duplicate/idempotency result.

Acceptance target:

```text
Transfer p95 <= 500 ms
Balance query p95 <= 500 ms
No unexpected financial invariant violation
No duplicate debit/credit
```

If target fails, report actual result and bottleneck. Do not hide failure by raising thresholds.

## 5. Team ownership: 3 BE + 1 FE/BE (Đội hình 4 thành viên)

Detailed individual work orders:

- [BE-A — Identity, Security, Account Lifecycle, CI Backend](./11-be-1-identity-security-onboarding.md) (Gộp BE-1 + BE-2 Account Lifecycle)
- [BE-3 — Transfer, Financial Consistency, Idempotency](./13-be-3-transfer-financial-consistency.md)
- [BE-B — Audit, Test Support, Integration, Invariant Data](./14-be-4-audit-risk-integration.md) (Gộp BE-4 + BE-2 Data Support)
- [FE/BE — Contract, E2E, k6, Fraud Flagging, CI Frontend](./15-fe-be-contract-e2e-load-ci.md)

### BE-A — Identity, Security & Account Lifecycle
* **Kỹ thuật làm chủ:** `Authentication & RBAC (3 Roles)`, `Token Rotation & Session Revocation`, `Pessimistic Locking (SELECT ... FOR UPDATE)`.
* **Phạm vi code:** `identity/**`, `customer/**`, `account/**`, `SecurityConfiguration`, `OnboardingController`, auth/session/OTP/PIN/recovery, rate-limit, account lock/status, seed concurrency (Pessimistic lock số dư) và `.github/workflows/backend-ci.yml`. Không sửa `transfer/**`, disposable test support, k6 script hay FE CI.

### BE-3 — Transfer Financial Consistency & Idempotency
* **Kỹ thuật làm chủ:** `Idempotency-Key Pattern`, `Strong Transactional Consistency (ACID Rollback)`, `Deadlock Prevention (Sorted Resource Locking)`.
* **Phạm vi code:** `transfer/**`, `banking-common`, transfer rollback/timeout, overdraft, transfer idempotency race, opposite lock, duplicate OTP confirm và block-vs-confirm transfer behavior. Sử dụng contract từ BE-A; không sửa implementation auth/account.

### BE-B — Audit, Test Infrastructure, Data Mocking & Integration Lead
* **Kỹ thuật làm chủ:** `Immutable Audit Trail`, `Distributed Tracing (Correlation-ID)`, `Resilience & Recovery Harness`, `Financial Invariant SQL Query`.
* **Phạm vi code:** `audit/**`, `banking-common`, `docker/`, disposable PostgreSQL test support, DB-unavailable/restart test harness, container image scan, script sinh 100 tài khoản mẫu + câu truy vấn bảo toàn số dư ($\sum Balance_{before} = \sum Balance_{after}$), evidence matrix và final acceptance report.

### FE/BE — Contract, E2E, k6, Fraud Flagging & CI Frontend
* **Kỹ thuật làm chủ:** `Multi-role Web Experience (Customer/Operator/Auditor)`, `Offline Retry UX with Idempotency`, `Load Testing with k6 (50 VUs)`, `Rule-based Anomaly & Fraud Flagging`.
* **Phạm vi code:** `frontend/**`, browser 3-role E2E, unknown-outcome UI, `tests/load/banking-mvp.js`, `.github/workflows/frontend-contract.yml`, routing checks, và **Rule-based Anomaly/Fraud Detection logic & UI** (>5M hoặc >5 lần/10p).

## 6. Shared-file ownership and conflict prevention

Only one owner edits each shared file at a time:

| Shared area | Owner |
|---|---|
| `contracts/openapi.yaml` | BE-B merge; module owner proposal |
| `pom.xml` | BE-B điều phối + owner liên quan |
| `application*.yml` | BE-B |
| `SecurityConfiguration` | BE-A; đổi cross-role cần BE-B review |
| Global error/correlation | BE-B; BE-A review auth |
| Flyway numbering | BE-B |
| `docker-compose.yml`, `Dockerfile` | BE-B |
| `.github/workflows/backend-ci.yml` | BE-A; BE-B review |
| `.github/workflows/frontend-contract.yml` | FE/BE; BE-B review |
| `AccountUiApiRegressionPostgresTest.java` | BE-A |
| `TransferUiApiRegressionPostgresTest.java` | BE-3 |
| `DisposablePostgresTestSupport.java` | BE-B; BE-A/3 consume only |
| `DatabaseFailureRecoveryTest.java` | BE-B harness; BE-A/3 assertions by contract |
| Team progress tracking | [progress.md](../../progress.md) — Dùng chung cả nhóm |

Rules:

- One PR owns one module or one acceptance scenario.
- Do not mix migration, unrelated refactor and feature logic in one PR.
- Do not edit old applied migrations. Add forward migration only.
- No commit or push without explicit team/user instruction.
- No `.env`, credentials, OTP, PIN, JWT or full account number in Git.
- New schema change must state migration number, rollback compatibility and affected FE contract.

## 7. Merge gate

Every PR must include:

1. Scope and module owner.
2. Relevant tests and exact command.
3. Actual pass/fail/skipped result.
4. OpenAPI impact: `none` or explicit approved diff.
5. DB/migration impact: `none` or new forward migration.
6. Role/ownership impact.
7. Idempotency and financial invariant impact.
8. FE impact and handoff note.
9. Secret/log/privacy review.
10. No unrelated generated files or formatting churn.

Integration Owner runs before merge:

```powershell
# Backend
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -DskipTests package
mvn -q test

# Contract
python scripts/validate-openapi.py

# Frontend
cd ..\frontend
npm run typecheck
npm test
npm run api:check
npm run build

# Diff hygiene
cd ..
git diff --check
```

Use environment variables for local JWT secret and database settings. Never paste secret values into evidence.

## 8. Execution order

### Step 0 — Documentation alignment

- Mark stale continuation records.
- Current migration baseline is V1–V7: V1–V5 phase evidence remains historical; V6 recovery and V7 registration verification evidence are recorded in dated specs.
- Keep historical phase notes unchanged where they record past evidence; clarify phase scope instead of rewriting past test claims.
- Use this file as current acceptance/team plan.
- Use [10-detailed-team-task-handover.md](./10-detailed-team-task-handover.md) for individual task ownership, test and handoff detail.

### Step 1 — Failure/recovery tests

BE-4 owns disposable test support and resilience harness. BE-3 owns transfer rollback and timeout/retry behavior plus transfer assertions inside restart/DB-outage scenarios. BE-1 owns OTP dispatch failure and auth-related assertions. FE/BE owns unknown-outcome/reconciliation UI and evidence.

### Step 2 — Concurrency tests

BE-1 owns auth/session/registration concurrency. BE-2 owns seed/account concurrency. BE-3 owns transfer overdraft, duplicate transfer key, opposite direction, duplicate confirm and block-vs-confirm transfer assertions. BE-4 owns disposable PG support and verifies isolation.

### Step 3 — k6 load test

FE/BE creates `tests/load/banking-mvp.js` and disposable scenario. BE-2 provides seed SQL, balance dataset and invariant query plus pool/lock metrics runbook. BE-3 reviews financial invariant checks. BE-4 reviews script and evidence format. Run baseline, save raw summary and acceptance report.

### Step 4 — Security and observability review

BE-1 owns dependency, secret and sensitive-log scans plus OTP/mailbox security. BE-4 owns container image scan review and final scan disposition. BE-2 provides DB metrics. FE/BE verifies browser-visible cookie/CORS/routing behavior.

### Step 5 — Local final acceptance

Run full BE/FE targeted gates, API smoke, browser E2E and acceptance matrix. Record actual results under `docs/projects/backend-mvp/evidence/`.

### Step 6 — Cloud decision and deployment

Only after local gate passes, choose one provider and create separate deployment spec. Cloud provisioning is outside this file until provider, region and budget are approved.

## 9. FE handoff contract

BE must provide before FE marks final E2E:

- Current `contracts/openapi.yaml`.
- Stable error code matrix.
- Role/ownership matrix.
- Demo seed procedure using environment-supplied credentials.
- Local OTP mailbox guard procedure.
- Disposable test data instructions.
- Endpoint status transition examples.
- Known limitations: best-effort risk flagging, no real SMS/Email delivery, no ML, no cancel/resend transfer endpoint.

FE must provide:

- Browser E2E command and result.
- Screenshots only with synthetic data.
- Role coverage for Customer, Operator and Auditor.
- Unknown outcome/reconciliation evidence.
- Responsive and accessibility limitations.

## 10. Acceptance checklist

### Functional

- [x] Customer registration, login, PIN and recovery implemented.
- [x] Customer account reads implemented.
- [x] Operator lookup, seed, block and unblock implemented.
- [x] Customer transfer and OTP step-up implemented.
- [x] History/status implemented.
- [x] Audit and risk read APIs implemented.
- [x] FE connected to real API and browser-tested for three roles.

### Financial/security

- [x] VND integer-string money model.
- [x] Account ownership and role checks.
- [x] Idempotency storage and basic replay.
- [x] Ordered account locking implementation.
- [x] OTP/PIN/password secrets excluded from API/log policy.
- [ ] Deterministic rollback after injected credit failure.
- [ ] Timeout-after-commit retry evidence.
- [ ] Application restart recovery evidence.
- [ ] DB outage fail-closed evidence.
- [ ] Concurrent overdraft proof.
- [ ] Concurrent duplicate idempotency proof.
- [ ] Opposite-direction deadlock test.
- [ ] Block-vs-confirm race proof.

### Quality/operations

- [x] Backend build/test evidence recorded.
- [x] Frontend typecheck/test/build evidence recorded.
- [x] OpenAPI validation and generated type check recorded.
- [x] Docker/Testcontainers local evidence recorded.
- [ ] k6 50 VU / 20 RPS / 10-minute report.
- [ ] Dependency vulnerability scan.
- [ ] Container image scan.
- [ ] Full sensitive-log scan.
- [ ] CI pipeline evidence.
- [ ] Cloud deployment and rollback evidence.
- [ ] Final acceptance report marked `ACCEPTED` or `NOT ACCEPTED`.

## 11. Current handover

Next work starts with publishing BE-4 disposable PostgreSQL test-support contract and freezing shared interfaces. BE-1/2/3 can implement module-local work and unit tests in parallel; PostgreSQL integration cases start when the shared support is available. Do not add ML, ledger, microservices, Kafka, Outbox, review workflow or real notification delivery.

Keep local Docker/Testcontainers databases isolated. Do not run `docker compose down -v`, `DROP`, `TRUNCATE` or bulk deletion against retained data.

After each task, update its implementation note with actual commands and outcomes. Update this file only with verified status; do not convert planned work into pass claims without fresh evidence.
