# BE-B — Plan thực thi hoàn chỉnh (sau 4 phản biện)

**Vai trò:** BE-B / Integration Lead. Owner `audit/**`, `risk/**` backend, `shared/error/**`, disposable PostgreSQL support, resilience harness, seed SQL + invariant query cho k6, image scan, OpenAPI merge, migration numbering, evidence matrix, final report.

**Không sửa:** `identity/**`, `customer/**`, `account/**`, `transfer/**` implementation, `frontend/**` flow, `tests/load/**` implementation, migration cũ đã apply.

**Tài liệu gốc giữ nguyên:** [14-be-4-audit-risk-integration.md](./14-be-4-audit-risk-integration.md). File này là plan thực thi đã vá 4 phản biện.

---

## Gói BEB-0 — Merge gate + evidence template

**Scope:** `docs/projects/backend-mvp/evidence/_TEMPLATE.md`, gate 10 điểm cho mọi PR.

**Target:** Mọi PR có cùng checklist. Evidence có trạng thái chuẩn.

**Điều kiện vào:** Không cần input member khác. Đọc file 09 mục merge gate và file 10 protocol chung.

**Steps:**

1. Tạo `evidence/_TEMPLATE.md`: Requirement | Owner | Command | Evidence link | Status | Gap | Next.
2. Status chỉ: `PLANNED` | `IMPLEMENTED` | `VERIFIED` | `BLOCKED` | `NOT_ACCEPTED`. Cấm `ACCEPTED` lẻ tẻ.
3. Công bố gate 10 điểm: owner + scope rõ; test + exact command; pass/fail/skipped thật; OpenAPI none hoặc diff approved; DB/migration none hoặc V mới forward-only; role impact; money/idempotency impact; FE handoff note; secret/log review; không churn unrelated.
4. Thiếu 1 điểm thì request changes, không merge.

**Test:** `git diff --check` tại root `d:\work\Xgame\XCreative\yuiyL\Cloud\cloude`.

**Acceptance:** `_TEMPLATE.md` tồn tại. Mọi PR sau viện dẫn đủ 10 điểm.

**Output:** `evidence/_TEMPLATE.md`.

**Handoff:** Gửi template + gate cho BE-A, BE-3, FE/BE. Nhận evidence theo template, không nhận link design docs thay log pass.

---

## Gói BEB-1 — Audit/risk correctness

**Scope:** `be/src/main/java/com/bank/simulator/audit/**`, `be/src/main/java/com/bank/simulator/risk/**`, test tương ứng. Lỗi business module khác thì trả đúng owner.

**Target:** Append-only, redaction, phân quyền, cursor ổn định, risk threshold, `AFTER_COMMIT` isolation, không duplicate flag.

**Phụ thuộc:** Implement độc lập. Tổng hợp matrix cuối cần evidence thô BE-A/BE-3 về auth audit events và transfer audit facts. Thiếu thì row liên quan mark `BLOCKED`.

**Steps:**

1. Audit events đủ: register, counter create, account create, seed, block/unblock, transfer, PIN, recovery, auth outcome.
2. Mỗi row `audit_events` đủ actor/type/target/outcome/time/correlation/summary. Thiếu field là fail. Bảng thật là `audit_events`, không phải `audit_logs`.
3. Redaction: grep `password`, `hash`, `PIN`, `OTP`, `token`, `secret`, account full trong response + log + DB payload. Có hit thì fix.
4. Append-only: `JdbcAuditWriter` chỉ `INSERT`. Không tồn tại update/delete API audit.
5. Phân quyền: Customer không query audit. Auditor/Admin đúng role. Test ma trận 403.
6. Cursor/filter ổn định: repeat cùng cursor cùng thứ tự. Check limit/cursor/eventType/actorId/from/to.
7. Risk 5M đúng không flag. 5M+1 flag `LARGE_TRANSFER` v1.
8. 5 outgoing completed/10p không flag. Cái thứ 6 flag `HIGH_FREQUENCY` v1. Loại trừ incoming/pending/failed.
9. `AFTER_COMMIT`: kill risk bean trong test thì transfer vẫn commit.
10. Re-eval cùng `(transfer_id, rule_id, version)` không duplicate row.

**Lưu ý scope:** FE/BE own fraud UI/logic hiển thị. BE-B vẫn own risk backend nêu trên.

**Test:**

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Audit*Test,*Risk*Test" test
```

**Acceptance:** Audit đủ events + redaction 0 hit + append-only proof. Risk pass 5M/5M+1, 5/6 frequency, `AFTER_COMMIT`, no-duplicate.

**Output:** `evidence/BE4-audit-risk.md` + test log.

**Handoff:** Cho BE-A: auth audit yêu cầu, scan format. Cho BE-3: transfer audit/risk yêu cầu, invariant/report template. Cho FE/BE: contract/error matrix chốt, E2E matrix template, screenshot policy, CI artifact path, k6 review checklist.

---

## Gói BEB-2 — Disposable PostgreSQL support (đã vá phản biện 1)

**Scope:** Tạo mới `be/src/test/java/com/bank/simulator/DisposablePostgresTestSupport.java`. Infrastructure only.

**Target:** BE-A và BE-3 dùng chung một lifecycle, isolated database/schema, không retained data, không fork base riêng.

**Vấn đề đã xác nhận:** `FoundationPostgresTest` và `UiApiRegressionPostgresTest` hiện mỗi class tự khai báo `@Container static PostgreSQLContainer`. Support mới không thay cơ chế container bằng cách viết class tách rời. Support phải tái dùng `DynamicPropertySource` + `DOCKER_HOST` pipe Windows theo evidence Testcontainers hiện có.

**Phụ thuộc:** Không chờ BE-A/BE-3. Đây là contract BE-B publish. BE-A/BE-3 làm unit test và implementation module-local trong lúc chờ. Chỉ PG integration test mới `BLOCKED` khi support chưa có.

**Steps:**

1. Gom lifecycle PostgreSQL/Testcontainers dùng chung: một `@Container` pattern chuẩn, `DynamicPropertySource` cho datasource URL/username/password, JWT test secret qua env/config test.
2. Cung cấp helper `clearBusinessTables()` gọi trong `@BeforeEach`: `TRUNCATE risk_flags, transfers, audit_events, idempotency_records, customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE`. Chỉ xóa bảng nghiệp vụ, giữ Flyway migration history.
3. API fixture/reset explicit: tạo disposable instance/schema, reset chỉ disposable schema/instance. Cấm cleanup vào shared DB.
4. Publish contract consume cho BE-A/BE-3: profile, fixture isolation, safe reset API, ví dụ gọi helper. Cấm fork private container/base. Cần helper mới thì proposal gửi BE-B.
5. Legacy `UiApiRegressionPostgresTest.java` frozen cho tới khi BE-B điều phối tách xong. Không để BE-A/3 sửa concurrent.

**Test:** BE-B verify bằng suite audit/risk trên support mới. BE-A/BE-3 verify consume khi tích hợp.

**Acceptance:** Không còn container riêng cho PG integration mới. Không flaky do data đè nhau giữa suite BE-A và BE-3.

**Output:** `DisposablePostgresTestSupport.java` + đoạn contract trong `BE4-resilience.md`.

**Handoff:** Gửi contract cho BE-A/BE-3. Lỗi behavior thuộc module owner, lỗi harness thuộc BE-B.

---

## Gói BEB-3 — Resilience harness (đã vá phản biện 2)

**Scope:** Tạo mới `be/src/test/java/com/bank/simulator/DatabaseFailureRecoveryTest.java`. BE-B own lifecycle/command/isolation/evidence. Không sửa production business logic.

**Target:** Fail-closed khi DB unavailable. Persisted state khi restart backend.

**Vấn đề đã xác nhận:** `postgresContainer.stop()` rồi `start()` đổi JDBC port động. Giữ Spring context cũ khiến `HikariCP` không reconnect. Không dùng stop/start trần cho case outage chính.

**Phụ thuộc:** Cần BEB-2 có trước vì harness chạy trên disposable stack. Cần health route `GET /health` trả `200 UP` hoặc `503 DOWN` với `{status, timestamp}`. Cần fixture idempotency key từ BE-3 cho case restart retry. Thiếu fixture thì lifecycle vẫn làm, case retry mark `BLOCKED`. Assertions: BE-3 own transfer, BE-A own auth/OTP, BE-B own lifecycle/evidence.

**Steps:**

1. DB unavailable ưu tiên `Toxiproxy` cắt network TCP rồi mở lại. Phương án dự phòng: stub `DatabaseHealthIndicator`/`DataSource` fail-closed ở MVC slice, hoặc test `HealthController` với indicator unavailable.
2. Assert health `503` + safe shape `{status, timestamp}`. Gọi transfer, assert không false success.
3. Restore dependency, assert recovery. Ghi container IDs + JDBC URL trước/sau.
4. Nếu stop container thật: bắt buộc `@DirtiesContext` + rebuild datasource sau restore. Ghi rõ lý do dùng stop thật trong evidence.
5. Application restart: request trên disposable stack, restart chỉ disposable backend process/container sau commit hoặc controlled boundary, retry cùng idempotency key, assert persisted transfer/balance/idempotency result.
6. Không stop retained/shared services. Không `docker compose down -v`, `DROP`, `TRUNCATE`, bulk deletion vào retained data.
7. Ghi exact setup/teardown, command, container IDs, expected/actual, skipped/blocked reason.

**Test:**

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="DatabaseFailureRecoveryTest" test
```

**Acceptance:** DB-unavailable fail-closed pass + restored proof. Restart retry same-key proof.

**Output:** `evidence/BE4-resilience.md` + log harness.

**Handoff:** BE-3 review transfer assertions. BE-A review auth/OTP assertions. FE/BE đối chiếu unknown-outcome UI.

---

## Gói BEB-4 — Seed dataset + invariant query (đã vá phản biện 3)

**Scope:** `scripts/seed-k6-accounts.sql` hoặc script mock 100 account, `queries/verify-financial-invariant.sql`. Không sửa `tests/load/banking-mvp.js` vì FE/BE own.

**Target:** FE/BE chỉ việc chạy, không tự đoán số dư. BE-3 review financial assertions.

**Vấn đề đã xác nhận:** V1 schema có FK `accounts.customer_id`, `customers.user_id` unique, `users` cần phone regex + email normalized + password hash, `customer_pins` cần pin hash + `configured_at`, `user_roles` cần role. Insert chay `accounts` gây 401/404 ở login/transfer k6.

**Phụ thuộc:** Cần BE-A account DTO/status/error contract để seed khớp eligible state. Chưa chốt thì dùng contract draft đã thống nhất, ghi version. FE/BE own script k6. Thiếu dataset thì k6 run `BLOCKED`, skeleton script vẫn làm được.

**Steps:**

1. Seed đủ chuỗi `users → user_roles → customers → customer_pins → accounts`. 100 customers + 100 accounts synthetic, balance known ví dụ `10,000,000 VND`, currency `VND`, status `ACTIVE`, `account_number` 12 số.
2. Password hash test + PIN đã configure nếu k6 cần login. Credentials qua env, không commit secret.
3. Invariant query final balance: `SUM(balance_before) = SUM(balance_after)`. Không thêm Ledger nếu chưa định nghĩa rõ trong spec.
4. Cung cấp expected row counts, known balances, pool/lock metrics runbook: PostgreSQL version/config, Hikari config, lệnh active/idle connections, lock wait/deadlock, Docker CPU/RAM.
5. Seed và cleanup chỉ disposable DB. Không chạy load trên DB giữ lại.
6. Dán output query thật vào evidence. Không dùng mock thay PG cho claim persistence.

**Test:** Seed trên disposable instance, chạy invariant query trước/sau sample transfer để verify query đúng.

**Acceptance:** FE/BE confirm dataset + error codes chạy được. BE-3 confirm invariant query đủ dùng.

**Output:** seed SQL, invariant SQL, `BE4-k6-dataset.md` hoặc mục evidence BE-B, DB metrics runbook.

**Handoff:** Cho FE/BE: seed SQL disposable, balance dataset known, invariant query, expected row counts. Cho BE-3: invariant summary. Cho BE-A: seed/block audit facts nếu seed ghi audit.

---

## Gói BEB-5 — Image scan + shared files + OpenAPI merge

**Scope:** Image scan report. Điều phối `contracts/openapi.yaml` merge duy nhất, migration numbering, `pom.xml` / `application*.yml` / docker files.

**Target:** Scan có disposition rõ. Shared files một owner merge, không drift lén.

**Phụ thuộc:** Cần proposal approved từ module owner trước khi merge OpenAPI hoặc cấp migration mới. Drift không proposal thì revert.

**Steps:**

1. Chạy image scan bằng tool team chốt. Ghi image digest, tool/version, severity summary, disposition.
2. OpenAPI chỉ merge proposal có approve module owner + BE-B.
3. Migration cấp số tiếp theo, check forward-only, rollback compatibility note. Không sửa migration cũ.
4. Đổi `pom.xml`/yml/docker phải ghi lý do + ảnh hưởng BE/FE + test lại.
5. Review k6/CI/scan member khác ở mức checklist: có file, có run log, không secret. Không viết hộ implementation.
6. Giữ `git diff --check` sạch. Churn unrelated thì tách PR.

**Cảnh báo:** Không paste secret vào report. Không commit `.env`, credentials, OTP, PIN, JWT, full account number.

**Acceptance:** Scan đủ digest + tool/version + severity + disposition. OpenAPI version đồng bộ. Baseline V1–V7 giữ nguyên trừ V mới approved.

**Output:** Image scan report evidence BE-B, OpenAPI merge log, migration numbering log.

**Handoff:** Gửi OpenAPI version chốt + error/role matrix cho FE/BE. Gửi migration số mới cho owner cần schema change.

---

## Gói BEB-6 — Evidence matrix sống + final acceptance (đã vá phản biện 4)

**Scope:** Matrix P0 sống, `evidence/FINAL-ACCEPTANCE.md`, FE handoff package một bản duy nhất.

**Target:** Mỗi `VERIFIED` bấm link ra evidence thật. Kết luận duy nhất `ACCEPTED` hoặc `NOT_ACCEPTED`.

**Phụ thuộc:** Cần evidence thô BE-A/BE-3/FE-BE: exact command/output/environment/status. Thiếu thì mark `BLOCKED`, không suy diễn pass. BE-B chạy lại full gate trên máy sạch trước final report.

**Steps:**

1. Cập nhật matrix liên tục: thêm row từ PR merged, `PLANNED` thành `IMPLEMENTED` khi code xong, thành `VERIFIED` chỉ khi BE-B chạy lại pass trên máy sạch.
2. Link evidence thật: file path + commit hash. Không link design docs thay pass.
3. Row `BLOCKED` ghi blocker + owner. Blocker kéo dài escalate user/lead.
4. Giữ V1–V7, OpenAPI version, test counts BE/FE đồng bộ. Số stale BE-B fix ngay.
5. Tổng hợp P0: rollback, timeout, restart, DB outage, overdraft, idempotency, opposite lock, block-vs-confirm, k6, scan, CI BE/FE, FE 3-role E2E, OpenAPI/build/test.
6. Mỗi dòng: `VERIFIED`/`BLOCKED`/`NOT_ACCEPTED` + evidence link + command.
7. Kết luận một dòng: `ACCEPTED` hoặc `NOT_ACCEPTED` + gaps còn lại.
8. FE handoff một bản duy nhất: OpenAPI version, error/role matrix, demo env setup, mailbox guard, synthetic data, state examples, audit/risk rows mẫu, health/Swagger/base URL, smoke/E2E/k6 commands, limitations best-effort risk, no real SMS, no ML, no cancel/resend.

**Setup môi trường trước gate (vá phản biện 4):**

```powershell
pip install -r D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be\requirements-openapi.txt
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm install
```

Thiếu dependency ghi `BLOCKED` do env, không ghi pass giả.

**Full gate BE-B chạy trước final report:**

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

Fail ở đâu ghi đúng chỗ đó.

**Acceptance:** Matrix đủ P0 rows chuẩn status + link thật. Final một kết luận. FE handoff một package.

**Output:** matrix sống, `evidence/FINAL-ACCEPTANCE.md`, FE handoff package.

**Handoff:** Module owners check chéo phần mình. User/lead bấm từng link `VERIFIED`.

---

## Thứ tự thực hiện

1. BEB-0 template + gate.
2. BEB-2 disposable support contract để mở khóa PG integration cho BE-A/BE-3.
3. BEB-1 audit/risk song song BEB-4 seed/invariant.
4. BEB-3 harness sau khi BEB-2 xong.
5. BEB-5 scan + shared files xuyên suốt.
6. BEB-6 matrix liên tục, final report khi P0 evidence đủ.
