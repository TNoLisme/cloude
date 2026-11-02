# BE-B — Audit, Test Infrastructure, Data Mocking & Integration Lead (Gộp BE-4 + BE-2 Data)

**Người phụ trách:** BE-B / Integration Lead  
**Reviewer:** Tất cả thành viên review chéo phần mình  
**Tiến độ chung:** Cập nhật tại [progress.md](../../progress.md)  
**Kỹ thuật làm chủ để bảo vệ đồ án:**  
1. `Immutable Audit Trail`: Nhật ký kiểm toán bất biến (chỉ `INSERT`, cấm sửa/xóa), che giấu thông tin nhạy cảm (`PII masking`).
2. `Distributed Tracing (Correlation-ID)`: Sinh và gắn `Correlation-ID / Request-ID` xuyên suốt các service để truy vết log.
3. `Disaster Recovery & Failure Scenario`: Kịch bản sập Database/Kafka $\rightarrow$ ứng dụng báo lỗi an toàn, khi restart tự hồi phục trạng thái.
4. `Financial Invariant SQL Query & Data Mocking`: Script sinh 100 tài khoản mẫu và câu truy vấn SQL đo bảo toàn tổng số dư ($\sum Balance_{before} = \sum Balance_{after}$) cho bài test k6.

*(Lưu ý: Logic và UI Rule-based Anomaly/Fraud Detection đã được bàn giao cho **FE/BE** làm chủ).*

---

## 1. Vai trò trong team

BE-B là **Integration Lead** và **owner test support/resilience harness/data mock**: merge OpenAPI duy nhất, cấp số migration, điều phối `pom.xml`/`application*.yml`/docker, sở hữu disposable PostgreSQL support, DB-unavailable/restart harness, container image scan, script seed 100 account + invariant balance query cho k6, evidence matrix + final report.

## 2. Phạm vi chi tiết

### Được sửa

```text
be/src/main/java/com/bank/simulator/audit/**
be/src/test/java/com/bank/simulator/audit/**
be/src/test/java/com/bank/simulator/shared/health/** (test only)
be/src/test/java/com/bank/simulator/DisposablePostgresTestSupport.java (tạo mới, infrastructure only)
be/src/test/java/com/bank/simulator/DatabaseFailureRecoveryTest.java (harness)
scripts/seed-k6-accounts.sql (hoặc script mock 100 account)
queries/verify-financial-invariant.sql
docs/projects/backend-mvp/evidence/**
docs/projects/backend-mvp/09-acceptance-gap-and-team-plan.md
docs/projects/backend-mvp/10-detailed-team-task-handover.md
contracts/openapi.yaml (merge duy nhất, theo proposal đã approve)
pom.xml / application*.yml / docker-compose.yml / Dockerfile (điều phối)
```

### Không được sửa

- `identity/**`, `customer/**`, `account/**`, `transfer/**` implementation (chỉ review; lỗi trả đúng owner).
- `frontend/**` business flow (FE/BE own).
- `tests/load/**` implementation (FE/BE own).
- Migration cũ đã apply. V mới chỉ tạo khi có proposal approved.

## 3. Đầu vào

1. `audit/**`: AuditWriter record-only, append-only, actor/type/target/outcome/time/correlation/summary, redaction list.
2. Disposable PostgreSQL support `DisposablePostgresTestSupport.java`.
3. Evidence thô từ BE-A/3/FE: command + output + log, tổng hợp vào report cuối.

## 4. Quy trình thực hiện

### Bước 0 — Khóa checklist merge gate + evidence template (Tiên quyết — Thiết lập quy chuẩn)

1. Tạo `evidence/_TEMPLATE.md`: Requirement | Owner | Command | Evidence link | Status | Gap | Next. Status chỉ PLANNED | IMPLEMENTED | VERIFIED | BLOCKED | NOT_ACCEPTED. Cấm ACCEPTED lẻ tẻ.
2. Công bố PR 10 điểm gate:
```text
1 owner + scope rõ
2 test + exact command
3 pass/fail/skipped thật
4 OpenAPI none/diff approved
5 DB/migration none/V mới
6 role impact
7 money/idempotency impact
8 FE handoff note
9 secret/log review
10 không churn unrelated
```
Thiếu 1 điểm → request changes, không merge.

### Bước 1 — Audit/risk correctness (Độc lập, làm ngay)

Audit checklist (mỗi dòng 1 test hoặc query proof):
- Events đủ: register, counter create, account create, seed, block/unblock, transfer, PIN, recovery, auth outcome.
- Mỗi row: actor/type/target/outcome/time/correlation/summary. Thiếu field là fail.
- Redaction: grep password/hash/PIN/OTP/token/secret/account full trong response + log + DB audit payload. Có hit là fix.
- Append-only: không có update/delete API audit. Thử gọi trực tiếp repo update phải không tồn tại hoặc bị chặn.
- Phân quyền: Customer không query audit, Auditor/Admin đúng role. Test 403 matrix.
- Cursor/filter ổn định:分页 repeat same cursor trả cùng thứ tự.

Risk checklist:
- 5M đúng → không flag. 5M+1 → flag LARGE_TRANSFER v1.
- 5 outgoing completed/10p → không flag. Cái thứ 6 → flag HIGH_FREQUENCY v1.
- Incoming/pending/failed loại trừ khỏi frequency count. Test từng loại.
- AFTER_COMMIT: risk listener chạy sau commit. Kill risk bean trong test → transfer vẫn commit, không rollback.
- Re-eval cùng (transfer_id,rule_id,version) không duplicate row.
- Output `BE4-audit-risk.md` + test log.

Lệnh:
```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Audit*Test,*Risk*Test" test
```

### Bước 2 — Disposable PostgreSQL support và resilience harness

1. Tạo `DisposablePostgresTestSupport.java`: PostgreSQL/Testcontainers lifecycle cho test, isolated database/schema, no retained data, no destructive command against shared DB. BE-1/2/3 consume only; không fork base.
2. Tạo `DatabaseFailureRecoveryTest.java` cho harness:
   - DB unavailable: stop only disposable dependency, gọi health, assert `503` + safe `{status,timestamp}`, transfer không false success, restore dependency, assert recovery.
   - Application restart: chạy request trên disposable stack, restart only disposable backend process/container sau commit hoặc controlled boundary, retry cùng idempotency key, assert persisted transfer/balance/idempotency result.
3. BE-3 owns transfer-specific assertions; BE-1 owns auth/OTP dispatch assertions; BE-4 owns lifecycle, command, isolation and evidence. Harness không sửa production business logic.
4. Output: `BE4-resilience.md` gồm exact setup/teardown, command, container IDs, expected/actual, skipped/blocked reason.

### Bước 3 — Evidence matrix sống (Theo dõi liên tục theo từng PR)

1. Cập nhật matrix liên tục: thêm row mới từ PR merged, chuyển PLANNED→IMPLEMENTED khi code xong, →VERIFIED chỉ khi BE-4 chạy lại command pass trên máy sạch.
2. Link evidence thật (file path + commit hash), không link design docs thay pass.
3. Row BLOCKED ghi rõ blocker + owner. Không im lặng gánh; nếu có blocker kéo dài → escalate user/lead.
4. Giữ V1–V7 đồng bộ: migration baseline, OpenAPI version, test counts BE/FE. Số stale là BE-4 chịu trách nhiệm fix ngay.

### Bước 4 — Review PR + điều phối shared files (xuyên suốt)

- OpenAPI: chỉ merge proposal có approve module owner + BE-4. Drift không proposal → revert.
- Migration numbering: cấp số tiếp theo, check forward-only, rollback compatible note.
- `pom.xml`/yml/docker: đổi config phải ghi lý do + ảnh hưởng BE/FE + test lại.
- Review k6/CI/scan của người khác ở mức checklist (có file, có run log, không secret), không viết hộ.
- Giữ `git diff --check` sạch. Churn format unrelated → yêu cầu tách PR.

### Bước 5 — Final acceptance report (Tổng kết sau khi P0 evidence đủ)

1. Tổng hợp từng P0: rollback, timeout, restart, DB outage, overdraft, idempotency, opposite lock, block-vs-confirm, k6, scan, CI BE/FE, FE 3-role E2E, OpenAPI/build/test.
2. Mỗi dòng: status VERIFIED/BLOCKED/NOT_ACCEPTED + evidence link + command. Không lấy design docs làm pass.
3. Kết luận duy nhất 1 dòng: `ACCEPTED` hoặc `NOT_ACCEPTED` + gaps còn lại. Không mập mờ "cơ bản đạt".
4. Gửi FE handoff package 1 bản duy nhất: OpenAPI version, error/role matrix, demo env setup, mailbox guard, synthetic data, state examples, audit/risk rows mẫu, health/Swagger/base URL, smoke/E2E/k6 commands, limitations (best-effort risk, no real SMS, no ML, no cancel/resend).
5. Output `evidence/FINAL-ACCEPTANCE.md`.

| Row | Dependency | Required contract before implementation | Owner of failure |
|---|---|---|---|
| BE-1 auth/OTP tests | Disposable PG support from BE-4 | profile, fixture isolation, safe reset API | BE-1 behavior; BE-4 harness |
| BE-2 account/seed tests | Disposable PG support from BE-4 | DB lifecycle, synthetic fixture contract | BE-2 behavior; BE-4 harness |
| BE-3 transfer tests | BE-1 PIN/OTP contract + BE-2 lock/status contract + BE-4 PG support | method signatures, transaction boundary, error codes | module owner of failing behavior |
| FE/BE E2E | frozen OpenAPI + BE handoffs | DTOs, state/error matrix, synthetic users | FE/BE UI; corresponding BE module for API |
| FE/BE k6 | BE-1 auth setup + BE-2 dataset/query + BE-3 invariant | disposable seed, expected balances, correlation/error rules | FE/BE script; corresponding BE owner for API |
| BE-4 final report | evidence from all owners | exact command/output, environment, actual status | evidence-producing owner |

BE-4 publishes test-support contract before BE-1/2/3 start PostgreSQL integration tests. Module owners may begin isolated unit tests and implementation immediately. A missing input blocks only the dependent integration case, not unrelated tasks.

## 5. Dependencies and handoff contracts

| Work item | Depends on | Required handoff | If missing |
|---|---|---|---|
| BE-1/2/3 PostgreSQL integration tests | BE-4 test support | isolated lifecycle, fixture/reset API, no shared DB cleanup | module unit tests continue; PG evidence marked BLOCKED |
| BE-3 transfer tests | BE-1 OTP/PIN + BE-2 account lock contract | consume-once semantics, lock signature/order, status/error codes | tests use agreed contract draft; owner approval required before merge |
| DB outage/restart harness | Disposable stack and health route | service names, ports, health path, idempotency key fixture | do not stop retained/shared services; report BLOCKED |
| FE/BE E2E | frozen OpenAPI + module handoffs | DTO/error/state matrices + synthetic fixture | contract gate continues; affected flow BLOCKED |
| FE/BE k6 | BE-1 auth setup + BE-2 dataset + BE-3 invariant query review | credentials via env, seed SQL, expected balances | build script skeleton; do not run without disposable dataset |
| Final report | all owner evidence | exact command/output/environment/status | mark missing rows BLOCKED, never infer pass |

BE-4 publishes test-support contract before PostgreSQL integration cases start. This gates only PG integration; BE-1/2/3 can continue isolated implementation and unit tests.

## 6. Lệnh nghiệm thu

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Audit*Test,*Risk*Test" test
mvn -q -DskipTests package
mvn -q test
cd ..
python be/scripts/validate-openapi.py
cd frontend
npm run api:check
npm run typecheck
npm test
npm run build
cd ..
git diff --check
```

BE-4 chạy full gate này trước final report. Fail ở đâu ghi đúng chỗ đó, không sửa vội để xanh giả.

## 7. Tiêu chí đạt

- [ ] Audit đủ events + redaction 0 hit + append-only proof.
- [ ] Risk 5M/5M+1 + 5/6 frequency + AFTER_COMMIT + no-duplicate pass.
- [ ] DB-unavailable health/transfer fail-closed harness pass + restored dependency proof.
- [ ] Application restart retry same-key proof.
- [ ] Container image scan report has image digest, tool/version, severity summary and disposition.
- [ ] Matrix đủ P0 rows, status chuẩn, link evidence thật.
- [ ] PR gate 10 điểm áp mọi PR, có review log.
- [ ] V1–V7 + OpenAPI + counts đồng bộ.
- [ ] Final report 1 kết luận ACCEPTED/NOT_ACCEPTED.
- [ ] FE handoff 1 package duy nhất.

## 8. Nghiệm thu (ai check BE-4)

- Module owners check chéo phần mình trong matrix: sai là BE-4 sửa ngay.
- User/lead check final report: mỗi VERIFIED bấm link ra evidence thật. Link chết hoặc docs suông là fail.
- Fail nếu: dùng design docs thay test log, ACCEPTED lẻ tẻ, merge OpenAPI không proposal, migration cũ bị sửa.

## 9. Bàn giao

Tạo `evidence/BE4-audit-risk.md`, matrix sống, `evidence/FINAL-ACCEPTANCE.md`, FE handoff package.
Cho BE-1: log checklist, auth audit yêu cầu, scan format.
Cho BE-2: seed/block audit yêu cầu, DB metric fields cần.
Cho BE-3: transfer audit/risk yêu cầu, invariant/report template.
Cho FE/BE: contract/error matrix chốt, E2E matrix template, screenshot policy, CI artifact path, k6 review checklist.
Nhận từ BE-1/2/3/FE: command/output thật, DB/profile, test counts, security review, FE impact, limitation. Thiếu là mark BLOCKED, không tự bịa.

## 10. Cấm

Không fix hộ logic module khác. Không merge khi gate thiếu điểm. Không commit `.env`/secret/OTP. Không `down -v`/`DROP`/`TRUNCATE`. Không tự commit/push khi chưa lệnh rõ.
