# BE-ACC-12 — Verification evidence và handoff

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-12
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** **COMPLETED** — evidence Account 128/128 PASS, regression/Flyway 96/96 PASS, full `clean build --max-workers=1` **BUILD SUCCESSFUL** (106 class / **1244 test** / 0 failure / 0 error / 0 skip, 8m20s); T-CON-01/02 end-to-end và các mục ACC-DR vẫn BLOCKED theo task tương ứng
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần Architect + DBA + Security/QA
> **Branch:** `datht-backend-rework`; không tạo/không switch branch, không commit/push
> **Task phụ thuộc:** [BE-ACC-11](BE-ACC-11.md) — COMPLETED (Account scope)
> **Nguồn chuẩn:** `docs-common/03. SRS.md`; `04. Database design & analysis.md` §4.3.1; `07. API contracts.md` §8.2; `design/01. audit-traceability.md`; `design/02. consistency-concurrency.md`; `design/05. Modules contract.md` §7; [BE-ACC-02](BE-ACC-02.md)–[BE-ACC-11](BE-ACC-11.md); [DEC-ACC-010/011](../../../../DECISIONS.md)

---

## A. Task, phạm vi và revision

| Hạng mục | Giá trị |
|---|---|
| Task IDs | **BE-ACC-11** (P0, PostgreSQL concurrency/security/failure tests), **BE-ACC-12** (P1, verification evidence & handoff) |
| Revision | `07188e9c25ced5db5f934d2b75a771df299c9b0b` (branch `datht-backend-rework`) + working tree chưa commit |
| Phạm vi Account đã kiểm | Read APIs `API-ACC-01/02`, staff list `API-ADM-02`, ownership/C3, row lock, conditional debit/credit, rollback, schema `V8`, crypto/blind index |
| Ngoài phạm vi | Transfer/Ledger end-to-end (BE-ACC-08), durable audit (ACC-DR-15), staff data scope (ACC-DR-13), customer 404 (ACC-DR-10), tenant |
| Không thay đổi | Không sửa production code, `V1`–`V8`, contract, security chain trong task này |

---

## B. Môi trường thực thi

| Thành phần | Giá trị |
|---|---|
| OS | Linux `6.19.14+kali-amd64` (x64) |
| JDK | OpenJDK **21.0.11** (Gradle toolchain 21) |
| Gradle | Wrapper **8.14.3** |
| Docker | client/server **29.7.2** |
| PostgreSQL | Testcontainers `postgres:16-alpine` (mỗi integration class một database riêng) |
| Flyway | `V1`–`V8`, `validateOnMigrate=true`, `outOfOrder=false` |
| Security | Spring Security chain thật, `SessionAuthenticationFilter`, Problem writer, correlation/cache filter thật (không mock authorization) |
| Crypto | AES-256-GCM + HMAC-SHA256 thật; key synthetic từ `application-test.yml` |
| Workspace | Verification chạy trong bản sao cô lập `/home/kali/acc11-verify` (cùng source, `build/` riêng); sibling `/home/kali/digitalbanking-docs` → repo docs để test OpenAPI resolve `../digitalbanking-docs/openapi/digital-banking-v1.yaml` |

---

## C. Lệnh đã thực thi

```bash
# 1. Account module (13 class)
./gradlew test --tests 'com.simulatedbank.banking.account.*' --console=plain

# 2. Regression + Flyway + security chain + arch + context
./gradlew test \
  --tests 'com.simulatedbank.banking.migration.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.BackendApplicationContextTest' \
  --tests 'com.simulatedbank.banking.common.error.PostgreSqlErrorPipelineIntegrationTest' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurityServletTest' \
  --tests 'com.simulatedbank.banking.common.config.ObservabilityIntegrationTest' \
  --console=plain

# 3. Full build (bị chặn bởi môi trường — xem §D.3)
./gradlew clean build --max-workers=1 --console=plain
```

Lần chạy lặp: Account suite chạy **3 lần** (39s và 33s trong workspace/bản sao cũ; 1m08s trên bản sao mới sau reboot, gồm compile) và nằm trong full build 1244 test — tất cả PASS, không flaky.

---

## D. Kết quả

### D.1. Account module — PASS

| Suite | Kết quả |
|---|---|
| `AccountControllerPostgreSqlIntegrationTest` (HTTP/security) | 19/19 PASS |
| `AdminCustomerAccountsControllerPostgreSqlIntegrationTest` | 11/11 PASS |
| `AccountPersistencePostgreSqlIntegrationTest` | 5/5 PASS |
| `AccountSecurityLogAuditAdapterTest` (**mới**) | 3/3 PASS |
| `CommonAccountNumberProtectionAdapterTest` | 7/7 PASS |
| `AccountApiContractTest` | 6/6 PASS |
| `AccountAccessServiceTest` | 12/12 PASS |
| `AccountBalancePostgreSqlIntegrationTest` | 14/14 PASS |
| `AccountBalanceServiceTest` | 10/10 PASS |
| `AccountConcurrencyPostgreSqlIntegrationTest` (**mới**) | 6/6 PASS |
| `AccountLockPostgreSqlIntegrationTest` | 11/11 PASS |
| `AccountQueryServiceTest` | 14/14 PASS |
| `AccountReadPostgreSqlIntegrationTest` | 10/10 PASS |
| **Tổng** | **13 class / 128 test / 0 failure / 0 error / 0 skip** |

Log tổng hợp: [`evidence/BE-ACC-11/junit-account-suite.txt`](evidence/BE-ACC-11/junit-account-suite.txt).

### D.2. Regression + Flyway — PASS

| Suite | Kết quả |
|---|---|
| Migration `V1`–`V8` (7 class, gồm `AccountV8…` 10 test, `PostgreSqlFresh…`, `PostgreSqlFlyway…`) | 43/43 PASS |
| `ModuleBoundaryArchTest` | 18/18 PASS |
| `BackendApplicationContextTest` | 1/1 PASS |
| `HttpSecurityTest` + `HttpSecurityServletTest` | 10/10 + 2/2 PASS |
| `ObservabilityIntegrationTest` | 6/6 PASS |
| `PostgreSqlErrorPipelineIntegrationTest` | 8/8 PASS |
| **Tổng** | **14 class / 96 test / 0 failure / 0 error / 0 skip** |

Log tổng hợp: [`evidence/BE-ACC-11/regression-flyway.txt`](evidence/BE-ACC-11/regression-flyway.txt).

### D.3. Full `clean build` — PASS

| Lần | Thời điểm | Kết quả |
|---|---|---|
| 1 | 18/11/2026 08:11–08:50 UTC | **TIMEOUT 40 phút** khi chạy song song với `cleanTest build` của phiên khác: load average 128, RAM 11/12 GiB, swap cạn; worker treo ở suite ngoài Account |
| 2 | 18/11/2026 01:07 UTC (workspace repo) | **FAILED** `:test` — `Could not write XML test results` do hai phiên Gradle cùng ghi `build/test-results/test/` |
| 3 | 18/11/2026 09:22–09:30 UTC (sau reboot, không còn phiên song song) | **BUILD SUCCESSFUL** 8m20s — **106 class / 1244 test / 0 failure / 0 error / 0 skip**, EXIT=0 |

Lần 3 là bằng chứng full-suite chính thức: máy sạch, không có phiên Gradle khác, bản sao cô lập `/home/kali/acc11-verify`, sibling docs cho OpenAPI. Các lần 1–2 chỉ ra **rủi ro vận hành** (chạy song song trên cùng workspace), không phải lỗi test/code Account; không dùng chúng làm PASS.

Log: [`evidence/BE-ACC-11/full-build.txt`](evidence/BE-ACC-11/full-build.txt); log gốc `/home/kali/acc11-full-build.log`.

---

## E. Đối chiếu yêu cầu BE-ACC-12

| # | Yêu cầu | Trạng thái | Bằng chứng |
|---|---|---|---|
| 1 | Task IDs & scope | **DONE** | §A |
| 2 | Commit/revision | **DONE** | `07188e9` + working tree |
| 3 | Runtime environment/dependencies | **DONE** | §B |
| 4 | Commands | **DONE** | §C |
| 5 | Test results & counts | **DONE** | §D.1, §D.2, §D.3 (full build 1244/1244) |
| 6 | PostgreSQL concurrency verification | **PASS (Account scope)** | [BE-ACC-11](BE-ACC-11.md) §C |
| 7 | Security verification | **PASS** | [BE-ACC-11](BE-ACC-11.md) §D |
| 8 | Failure & rollback verification | **PASS (Account scope)** | [BE-ACC-11](BE-ACC-11.md) §E |
| 9 | Defects & fixes | **DONE** | [BE-ACC-11](BE-ACC-11.md) §H (1 test assertion; 0 production defect) |
| 10 | Limitations & blockers | **DONE** | §G |
| 11 | Evidence paths & logs | **DONE** | §F |
| 12 | Final status | **PASS (evidence/handoff)** | §H — full build PASS; T-CON-01/02 end-to-end vẫn BLOCKED BE-ACC-08 |

---

## F. Evidence paths

| Evidence | Đường dẫn |
|---|---|
| BE-ACC-11 report | [`BE-ACC-11.md`](BE-ACC-11.md) |
| JUnit summary Account | [`evidence/BE-ACC-11/junit-account-suite.txt`](evidence/BE-ACC-11/junit-account-suite.txt) |
| JUnit summary regression/Flyway | [`evidence/BE-ACC-11/regression-flyway.txt`](evidence/BE-ACC-11/regression-flyway.txt) |
| Full build summary | [`evidence/BE-ACC-11/full-build.txt`](evidence/BE-ACC-11/full-build.txt) |
| Raw JUnit XML | `/home/kali/acc11-verify/build/test-results/test/TEST-*.xml` (sinh lại bằng lệnh §C) |
| Full build log | `/home/kali/acc11-full-build.log` (lần chạy §D.3) |
| Task checklist | [`tasks/README.md`](tasks/README.md) |
| Quyết định | [DEC-ACC-010/011](../../../../DECISIONS.md) |

---

## G. Handoff kỹ thuật

### G.1. Chức năng đã hiện thực

| Năng lực | Lớp / artifact |
|---|---|
| Đọc account theo owner | `AccountController` (`API-ACC-01/02`), `AccountAccessService`, `AccountQueryService`, `JpaAccountReader` |
| Staff list theo customer | `AdminCustomerAccountsController` (`API-ADM-02`), `AccountAccessService.listCustomerAccounts` |
| Ownership & C3 | `AccountQueryService.getAccountView`, `AccountAuditPort` + `AccountSecurityLogAuditAdapter` |
| Row lock T2 | `AccountLockService`, `AccountLockPort`, `JdbcAccountLockAdapter` |
| Debit/credit có điều kiện | `AccountBalanceService`, `AccountBalancePort`, `JdbcAccountBalanceAdapter`, `AccountMoneyLockWait` |
| Crypto/blind index/mask | `CommonAccountNumberProtectionAdapter` (AES-256-GCM, HMAC-SHA256) |
| Schema | `V8__create_account_table.sql` (12 cột, PK IDENTITY, FK RESTRICT, UNIQUE blind index, 8 CHECK, 4 index) |

### G.2. API & contract

| API | Authz | Ghi chú |
|---|---|---|
| `GET /api/v1/accounts` | `ACCOUNT_READ_SELF` | Chỉ account của customer suy từ session; `status` optional; mask + money string scale 2; `no-store` |
| `GET /api/v1/accounts/{accountId}` | `ACCOUNT_READ_SELF` hoặc `ACCOUNT_READ` | Owner scope 403 + C3 khi khác chủ; staff scope theo permission; 404 không audit; 400 ID sai trước persistence |
| `GET /api/v1/admin/customers/{customerId}/accounts` | `ACCOUNT_READ` | `customerId` từ path chỉ chọn tập dữ liệu, không cấp quyền; 405 cho write-verb |

Port nội bộ: `account.api.AccountQuery` (read) và `account.api.AccountBalance` (`lockTransferAccounts`, `debitConditional`, `credit`) — transaction `MANDATORY`, chỉ Transfer T2 được gọi. Đổi signature là breaking change (BE-CON-45).

### G.3. Transaction & locking

- T2 do orchestrator mở; Account chỉ tham gia (`Propagation.MANDATORY`), không tự commit/rollback/retry.
- Lock 2 account bằng **một** câu `SELECT … WHERE account_id IN (?, ?) ORDER BY account_id FOR UPDATE`; thứ tự ASC giảm deadlock A↔B.
- `lock_timeout` mặc định **2s** chỉ khi caller chưa đặt (`SET LOCAL`); `55P03/40P01/40001/57014` là technical failure, map 503/retry toàn T2.
- Debit/credit là **một** câu conditional `UPDATE … RETURNING`, cập nhật đồng thời `available_balance`/`ledger_balance`; 0 row là kết quả business (`INSUFFICIENT_BALANCE`/`INACTIVE`) hoặc `INTERNAL_ERROR` cho credit.
- Isolation vận hành: PostgreSQL **READ COMMITTED**; test xác nhận không dirty read, snapshot theo statement, row lock serialize.

### G.4. Idempotency

Account **không** có idempotency key/dedup (DEC-ACC-007 quyết định 7). Retry an toàn dựa trên rollback toàn T2 + idempotency/state guard của Transfer (BE-ACC-08). Test `duplicateConcurrentDebitRequestsAreBothApplied…` khóa ranh giới này để tránh hiểu nhầm.

### G.5. Bảo mật & audit

- Authentication/authorization do shared security chain; controller fail-closed nếu thiếu session snapshot; không nhận `userId`/`customerId`/role từ query/body (anti-IDOR).
- Chỉ trả masked number `**** **** <last4>`, money string; không ciphertext/blind index/PII/token/stack trace; `Cache-Control: no-store`.
- Không có write route: POST/PUT/PATCH/DELETE trả 405 và dữ liệu không đổi.
- C3 `SECURITY_ACCESS_DENIED`: metric + structured log; lỗi audit/metrics không đổi 403. Durable `audit_log` vẫn BLOCKED (ACC-DR-15).

### G.6. Failure handling

| Loại | Hành vi |
|---|---|
| Business rejection | `INSUFFICIENT_BALANCE`, `INACTIVE`, 404, 403, 400 problem response; không partial write |
| Technical failure | DB/constraint/timeout lan truyền `DataAccessException`; transaction rollback toàn bộ; orchestrator map 5xx/retry |
| Constraint | `CHECK balance >= 0`, `available <= ledger`, `NUMERIC(19,2)`, `UNIQUE account_no_hash` là chốt cuối |
| Rollback | Debit + credit + mọi thay đổi cùng T2 bị bỏ; lock nhả khi transaction kết thúc |

### G.7. Phụ thuộc tích hợp

| Module | Phụ thuộc | Trạng thái |
|---|---|---|
| Identity | Session snapshot/authorities (`ACCOUNT_READ_SELF`, `ACCOUNT_READ`), role seed | Có runtime; test dùng session thật |
| Customer | `CustomerQuery.findByUserId` cho self read | Có runtime; failure injection **NOT_RUN** |
| Customer | `getCustomerView(customerId)` cho `owner.fullName`/404 | **BLOCKED** ACC-DR-10 |
| Admin | Route `API-ADM-02` do Account sở hữu; staff scope | Permission OK; data scope **BLOCKED** ACC-DR-13 |
| Transfer/Ledger | Consumer T2 thật, ledger entry, T-CON-01/02 đầy đủ | **BLOCKED** BE-ACC-08 |
| Audit | Durable `audit_log` | **BLOCKED** ACC-DR-15 |

### G.8. Điều kiện môi trường để tái hiện

1. Docker daemon chạy, user có quyền (Testcontainers pull `postgres:16-alpine`).
2. JDK 21; dùng `./gradlew` (wrapper 8.14.3).
3. Không chạy song song hai lệnh Gradle trên cùng project directory — dùng bản sao cô lập nếu workspace dùng chung; đặt sibling `digitalbanking-docs` cạnh bản sao (một số test đọc OpenAPI qua `../digitalbanking-docs/...`).
4. Đủ RAM cho test worker (`maxHeapSize=1g`) và container; khuyến nghị ≥ 4 GiB trống.
5. Chạy §C; đọc JUnit XML trong `build/test-results/test/`.

### G.9. Rủi ro tích hợp còn lại

| Rủi ro | Mức | Ghi chú |
|---|---|---|
| Chưa có T2 thật với Transfer/Ledger | Cao | Money invariant đã test nhưng chưa chứng minh ledger khớp/2 entry mỗi transfer |
| Staff đọc mọi customer khi có `ACCOUNT_READ` | Cao | ACC-DR-13; cần chốt trước release money path |
| `API-ADM-02` trả 200 rỗng cho customer không tồn tại | Trung bình | ACC-DR-10; không lộ dữ liệu nhưng sai error semantics |
| Audit C3 chỉ log/metric | Trung bình | ACC-DR-15; cần durable audit trước nghiệm thu |
| Không có tenant/NCC isolation | Trung bình | Schema v1 chưa có tenant |
| Chạy full suite song song trên cùng workspace | Trung bình | Lần chạy cô lập PASS 1244/1244; CI cần tách build dir/không chạy song song |
| Downstream Customer failure chưa inject | Thấp | Read path in-process; cần contract fault hook nếu owner yêu cầu |

---

## H. Trạng thái cuối

| Task | Trạng thái | Căn cứ |
|---|---|---|
| BE-ACC-11 | **COMPLETED (Account scope)** | 6 concurrency + 3 audit + 2 HTTP test mới; 128/128 PASS; 96/96 regression PASS; không sửa production code |
| BE-ACC-12 | **COMPLETED** | Evidence + handoff đầy đủ; full `clean build --max-workers=1` PASS (106 class / 1244 test / 0 fail / 0 error / 0 skip, 8m20s); T-CON-01/02 đầy đủ và các mục ACC-DR vẫn BLOCKED theo task tương ứng |

**Việc còn lại ngoài phạm vi BE-ACC-12:** khi BE-ACC-08 hoàn tất, bổ sung T-CON-01/02 end-to-end với ledger entry/transfer state; reviewer sign-off và commit/PR vẫn mở.
