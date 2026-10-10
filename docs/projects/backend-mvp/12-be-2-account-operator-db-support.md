# BE-2 — Account, Operator, Database Support

**Người phụ trách:** BE-2
**Reviewer:** BE-4 (Integration Owner)
**Mục tiêu:** Khóa đúng account/operator, chứng minh IDOR/lookup/seed/block bằng test PG thật, chốt trước lock API cho BE-3, cung cấp DB metrics + k6 dataset cho FE/BE.

---

## 1. Vai trò trong team

BE-2 là **owner duy nhất** `account/**`. Mọi quyết định read/lookup/seed/block thuộc BE-2.

BE-2 là **DB support**: review constraint/index, runbook pool/lock, chuẩn bị seed SQL + invariant query cho k6. FE/BE không tự đoán dataset, lấy từ BE-2.

BE-2 **không** ôm transfer orchestration, audit/risk, k6 HTTP scenario, FE CI.

## 2. Phạm vi chi tiết

### Được sửa

```text
be/src/main/java/com/bank/simulator/account/**
be/src/test/java/com/bank/simulator/account/**
be/src/test/java/com/bank/simulator/AccountUiApiRegressionPostgresTest.java (tạo mới, BE-2 own)
docs/projects/backend-mvp/evidence/BE2-*.md
```

### Không được sửa

- `identity/**`, `transfer/**`, `audit/**`, `risk/**` implementation. Cần đổi API thì proposal.
- `frontend/**`, `tests/load/**`, `.github/workflows/**`.
- Migration V1-V7. Cần V8+ qua BE-4.
- `OnboardingController.java`: BE-1 owns. BE-2 không sửa controller. Nếu onboarding cần account creation contract, gửi proposal method signature/input/output/transaction owner cho BE-1 + BE-4.
- `DisposablePostgresTestSupport.java`: BE-4 owns; BE-2 consumes only.

## 3. Đầu vào

1. `account/**`: entity, repository, service, controller operator.
2. `contracts/openapi.yaml`: account DTO, lookup rule, block codes.
3. `docs/.../04-account-operator.md`.
4. Disposable PostgreSQL support `DisposablePostgresTestSupport.java` from BE-4. Chưa có thì BE-2 có thể viết unit test/mock test; không fork container riêng. Integration test chạy sau khi support contract published.

## 4. Dependencies and contracts

| Work item | Depends on | Required handoff | If dependency missing |
|---|---|---|---|
| Account/operator implementation and unit tests | Current OpenAPI + account package | Existing DTO/status/error contract | Continue local work; record mismatch, no unapproved contract change |
| PostgreSQL account/seed tests | BE-4 disposable PG support | isolated lifecycle/reset API | Keep unit tests moving; mark integration proof blocked, no private container |
| Onboarding/default account behavior | BE-1 owns controller/orchestration | Account creation proposal only if boundary must change | Send proposal; do not edit `OnboardingController.java` |
| Block-vs-confirm | BE-3 transfer test boundary | status/lock assumptions | Verify account-side lock/status independently; BE-3 owns transfer race |
| k6 preparation | FE/BE script owner | seed SQL, known balances, invariant query, metrics commands | Publish fixtures/query contract; do not edit k6 script |

BE-2 owns account/seed concurrency and account-side assertions. BE-3 owns transfer concurrency and transfer-side assertions.

## 5. Quy trình thực hiện

### Bước 0 — Khảo sát + chốt lock API (Ưu tiên P0 — Mở khóa cho BE-3)

1. Liệt kê account endpoints + role + error codes hiện tại, ghi MISMATCH nếu khác OpenAPI.
2. Ngồi với BE-3 thống nhất chốt chữ ký:
```text
lockPair(idA, idB) ORDER BY id ASC
assertEligible(account): ACTIVE + currency VND + balance check
debit/credit contract: ai trừ, ai cộng, snapshot ở đâu
```
3. Ghi kết quả vào `BE2-lock-api.md`. BE-3 code transfer theo đó ngay, không chờ BE-2 xong impl.

Done bước 0 khi BE-3 confirm đọc hiểu và không block.

### Bước 1 — Account read + IDOR (Độc lập, làm ngay)

Ma trận test trên PG:

| ID | Input | Kỳ vọng |
|---|---|---|
| ACC-01 | Customer list accounts | chỉ thấy của mình, order `(opened_at DESC, id DESC)` |
| ACC-02 | Customer get id người khác | 404/403 concealment đúng contract, không lộ tồn tại |
| ACC-03 | Response | masked number only, balance string nguyên, currency VND |
| ACC-04 | BLOCKED account | vẫn đọc balance/history, không transfer/seed |
| ACC-05 | CLOSED account | đúng contract, không mutate |

Cách làm: TDD đỏ → fix → xanh từng case. Lệnh:
```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Account*Test,*OnboardingControllerMvcTest" test
```
Output `BE2-account-read.md`.

### Bước 2 — Lookup + seed + block (Nghiệp vụ Operator)

Lookup:
- `phone XOR email`: none/both/blank/wildcard → 400. Exact match only, normalize phone/email. Không thấy → 404. Customer/Auditor → 403. Audit không lưu raw value. Response không `accountNumber`.

Seed:
- amount 1..100M, VND only. Reject fraction/zero/negative/leading-zero/over-max. Reference 1..100, key 16..128.
- BLOCKED/CLOSED không seed. Replay cùng key/payload không credit lần 2. Khác payload → 409.
- Concurrent same-key (CountDownLatch + ExecutorService, dùng base BE-1): 1 ledger + 1 credit. Rollback full khi fail.

Block:
- ACTIVE↔BLOCKED, reason 1..500 trim. Repeat cùng state không duplicate audit. CLOSED → 409. Lock row. BLOCKED chặn seed/transfer.

Lệnh:
```powershell
mvn -q -Dtest="*Operator*Test,*Account*PostgresTest,AccountUiApiRegressionPostgresTest" test
```
Output `BE2-operator.md`.

### Bước 3 — DB invariant + metrics + k6 dataset (Hỗ trợ FE/BE & BE-3)

1. Verify metadata bằng query thật: balance >=0, currency/status check, account unique, one-default/customer, seed idempotency unique, index customer/opened, seed account/created. Dán output query vào evidence.
2. Runbook `BE2-db-metrics.md`:
- PostgreSQL version, Hikari pool config
- Xem active/idle connections
- Xem lock wait/deadlock
- Lệnh Docker CPU/RAM
- Dataset synthetic + cleanup disposable DB only. Không chạy load trên DB giữ lại.
3. Chuẩn bị cho FE/BE: seed SQL disposable, balance dataset known, invariant query final balance, expected row counts. FE/BE chỉ việc chạy, không tự đoán số.

### Bước 4 — Block-vs-confirm phối hợp BE-3 (Tích hợp cross-module)

- Setup: tạo AWAITING_OTP, race block vs confirm.
- BE-2 đảm bảo block lock row + revalidate. BE-3 đảm bảo confirm lock lại + revalidate dưới lock.
- Kết quả chốt: ineligible → FAILED đúng code, balance unchanged, OTP invalidate, terminal không ghi đè.
- Ghi chung vào `BE3-otp-race.md` + link từ BE-2.

## 6. Lệnh nghiệm thu

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Account*Test,*Operator*Test,*OnboardingControllerMvcTest" test
mvn -q -Dtest="*Account*PostgresTest,AccountUiApiRegressionPostgresTest" test
mvn -q -DskipTests package
cd ..
git diff --check
```

## 7. Tiêu chí đạt

- [ ] Lock API chốt trước, BE-3 confirm.
- [ ] IDOR/concealment pass PG.
- [ ] Lookup XOR + seed replay/concurrency + block idempotent pass.
- [ ] DB constraint evidence có output query thật.
- [ ] Runbook + k6 dataset gửi FE/BE.
- [ ] Dùng DisposablePostgresTestSupport của BE-4, không container riêng.
- [ ] Không sửa migration cũ.

## 8. Nghiệm thu

- BE-4 chạy lại lệnh mục 6, so pass count.
- BE-3 confirm lock API đủ dùng, block race pass.
- FE/BE confirm dataset + error codes chạy được.
- Fail nếu: mock thay PG cho concurrency, log raw phone/email, sửa `OnboardingController` không review BE-1.

## 9. Bàn giao

Tạo `evidence/BE2-account-read.md`, `BE2-operator.md`, `BE2-db-metrics.md`, `BE2-lock-api.md`.
Cho BE-3: lock/order/snapshot/debit-credit contract + metric lock.
Cho BE-4: seed/block audit facts + schema/index review.
Cho FE/BE: DTO, status matrix, XOR rule, key/replay header, reason rule, invalidation, error codes, dataset synthetic. Không full account number, không credential thật.

## 10. Cấm

Không chạm transfer/audit/risk impl. Không `down -v`, `DROP`, `TRUNCATE`. 1 PR 1 gói.
