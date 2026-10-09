# BE-2 — Kế hoạch Account, Operator và Hỗ trợ Database

**Người phụ trách:** BE-2  
**Reviewer:** BE-4  
**Phối hợp:** BE-1, BE-3, FE/BE  
**Trạng thái:** Kế hoạch thực thi; chưa nghiệm thu trước khi có output thật.  
**Mục tiêu:** Bảo đảm account ownership, account query, Operator lookup, seed, block/unblock và DB support đủ an toàn cho transfer và load test.

---

## 1. Phạm vi công việc

### 1.1 Bao gồm

- `GET /accounts`.
- `GET /accounts/{accountId}`.
- Exact Operator lookup bằng phone hoặc email.
- Seed balance và seed ledger.
- Seed idempotency.
- Concurrent duplicate seed.
- Account status `ACTIVE/BLOCKED/CLOSED`.
- Account row lock/status transition.
- Account masking và money string.
- DB constraints/index review.
- PostgreSQL pool/lock metrics cho k6.
- Dữ liệu synthetic cho E2E/load test.

### 1.2 Không bao gồm

- Login, JWT, OTP/PIN.
- Transfer orchestration/debit-credit.
- Audit query implementation.
- Risk evaluation.
- Cloud provisioning.
- Double-entry ledger.
- Sửa OpenAPI không qua approval.

---

## 2. File và vùng code

```text
be/src/main/java/com/bank/simulator/account/**
be/src/test/java/com/bank/simulator/account/**
be/src/main/java/com/bank/simulator/identity/web/OnboardingController.java
be/src/test/java/com/bank/simulator/identity/web/OnboardingControllerMvcTest.java
be/src/main/resources/db/migration/V1__*.sql ... V7__*.sql
```

Quy tắc shared controller:

- `OnboardingController` đang có Operator lookup và thuộc vùng giao với BE-1.
- Không sửa đồng thời trên hai branch/PR.
- Trước khi sửa DTO/mapping, ghi owner PR vào handoff note.
- Không sửa V1–V7. Schema change mới dùng V8+ do Integration Owner cấp.

---

## 3. Kết quả phải bàn giao

```text
- Account/operator implementation note.
- Test output thật.
- PostgreSQL constraint/lock evidence.
- DB metric runbook.
- FE response/error handoff.
- Synthetic dataset setup.
- Known gap list.
```

Mẫu:

```text
Task:
Owner:
Files changed:
API impact:
DB impact:
Role/ownership impact:
Commands:
Actual result:
Evidence:
FE action:
Next owner:
```

---

## 4. Kế hoạch thực thi theo bước

### Bước 1 — Đối chiếu contract

Đọc:

- `docs/baseline/api-and-team-contract.md`.
- `docs/baseline/mvp-requirements-and-architecture.md`.
- `docs/projects/backend-mvp/04-account-operator.md`.
- `contracts/openapi.yaml`.

Lập bảng:

| Flow | Endpoint | Role | Success | Error | Test hiện có | Gap |
|---|---|---|---|---|---|---|
| Account list | `/accounts` | Customer | 200 | 401/403 | ... | ... |
| Account detail | `/accounts/{id}` | Customer/policy | 200 | 403/404 | ... | ... |
| Lookup | `/operator/customers` | Operator/Admin | 200 | 400/403/404/429 | ... | ... |
| Seed | `/operator/accounts/{id}/seed-balance` | Operator/Admin | 201/200 replay | 400/409 | ... | ... |
| Block | `/operator/accounts/{id}/block` | Operator/Admin | 200 | 400/409 | ... | ... |
| Unblock | `/operator/accounts/{id}/unblock` | Operator/Admin | 200 | 400/409 | ... | ... |

### Bước 2 — Account reads và IDOR

Kiểm tra:

1. Customer chỉ thấy account của mình.
2. Customer A đoán account ID của Customer B không nhận data.
3. Status lỗi theo đúng concealment rule.
4. Account number luôn masked.
5. Balance serialize integer string.
6. Không dùng float/double.
7. Currency là `VND`.
8. `accountType`, `status`, `openedAt` đúng schema.
9. List ordering deterministic.
10. BLOCKED vẫn đọc được balance/history.
11. CLOSED response đúng contract.
12. Không trả raw account number trong API/log/fixture.

### Bước 3 — Exact Operator lookup

Kiểm tra:

- `phone XOR email`, đúng một filter.
- None/both/blank/malformed/partial/wildcard trả `400 VALIDATION_ERROR`.
- Phone normalize đúng.
- Email trim + lowercase `Locale.ROOT`.
- Exact equality, không `LIKE`, prefix hoặc listing.
- Không tìm thấy trả `404 CUSTOMER_NOT_FOUND`.
- Customer/Auditor trả `403 FORBIDDEN`.
- Rate limit trả `429 RATE_LIMITED` + `Retry-After`.
- Audit chỉ ghi filter type, không ghi raw search value.
- Response dùng `accountNumberMasked`.
- Nhiều account vẫn giữ đủ field được phép.

### Bước 4 — Seed balance

Kiểm tra input:

```text
amount: 1..100000000
currency: VND
reference: 1..100 ký tự
Idempotency-Key: 16..128 ký tự
```

Reject:

- zero.
- negative.
- fraction.
- leading zero không canonical.
- over max.
- currency khác VND.
- key thiếu/ngắn/dài.
- account BLOCKED/CLOSED.

Kiểm tra transaction:

1. Claim idempotency.
2. Lock account.
3. Validate status/currency/amount.
4. Insert seed ledger.
5. Credit balance.
6. Write audit.
7. Store replay result.
8. Commit một lần.

Kiểm tra replay:

- Cùng actor + operation + key + payload: không credit lần hai.
- Cùng key khác payload: `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent same-key: một ledger row, một balance mutation.
- Failure giữa các bước: rollback toàn bộ transaction.

### Bước 5 — Block/unblock

Kiểm tra:

- `ACTIVE -> BLOCKED`.
- `BLOCKED -> ACTIVE`.
- Reason bắt buộc, trim, 1–500 ký tự.
- Reason whitespace bị reject.
- Repeat cùng state trả state hiện tại.
- Repeat cùng state không duplicate audit.
- `CLOSED` trả `409 ACCOUNT_NOT_ELIGIBLE`.
- Status mutation lấy row lock.
- BLOCKED không seed.
- BLOCKED không là source/destination hợp lệ của transfer.
- Balance/history vẫn đọc được.
- Block-vs-confirm race phối hợp BE-3.

### Bước 6 — DB/index/metrics support

Kiểm tra metadata qua PostgreSQL:

- Balance non-negative.
- Currency/status checks.
- Account number unique.
- One default account/customer.
- Seed idempotency scope unique.
- Account customer index.
- Seed account/time index.

Cung cấp cho FE/BE và BE-4:

- PostgreSQL version.
- Hikari max/min pool.
- Query quan sát active/idle connections.
- Query quan sát lock wait/deadlock.
- Docker CPU/RAM command.
- Dataset setup command.
- Cleanup method chỉ cho disposable DB.

Không dùng shared retained DB cho load test.

---

## 5. Lệnh kiểm thử

MockMvc/unit:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Account*Test,*Operator*Test,*OnboardingControllerMvcTest" test
```

PostgreSQL integration:

```powershell
mvn -q -Dtest="*Account*PostgresTest,*Operator*PostgresTest,*UiApiRegressionPostgresTest" test
```

Build:

```powershell
mvn -q -DskipTests package
```

Kiểm tra diff:

```powershell
cd ..
git diff --check
```

Nếu test class chưa tồn tại, ghi `NOT IMPLEMENTED` và tạo task test riêng. Không ghi pass thay thế.

---

## 6. Tiêu chí nghiệm thu

### Bắt buộc pass

- Account list/detail pass ownership/IDOR.
- Full account number không xuất hiện.
- Lookup exact filter pass.
- Lookup role/rate-limit/error pass.
- Seed validation pass.
- Seed replay không double credit.
- Same key altered payload không mutate.
- Concurrent seed tạo một resource.
- Seed rollback pass.
- Block/unblock transition pass.
- CLOSED không reopen.
- Block-vs-confirm evidence có phối hợp BE-3.
- DB constraint/index evidence có output.
- No OpenAPI drift.

### Evidence cần lưu

```text
- Test command.
- Test count/pass/fail/skip.
- PostgreSQL/Testcontainers version.
- Schema metadata output.
- Concurrent seed result.
- Lock/constraint result.
- DB metric commands.
- Known limitation.
```

---

## 7. Handoff cho FE/BE

Gửi:

1. Account DTO field list.
2. Money string rule.
3. `ACTIVE/BLOCKED/CLOSED` behavior.
4. Ownership và `404` concealment.
5. Lookup phone/email XOR.
6. Normalize/exact-match rule.
7. Lookup masking rule.
8. Seed fields/limits.
9. Idempotency key/replay header.
10. Block/unblock reason rule.
11. Query invalidation sau seed/status.
12. Error codes:

```text
ACCOUNT_NOT_FOUND
CUSTOMER_NOT_FOUND
ACCOUNT_NOT_ELIGIBLE
IDEMPOTENCY_KEY_REUSED
VALIDATION_ERROR
RATE_LIMITED
FORBIDDEN
```

13. Synthetic Operator E2E dataset.
14. Account IDs chỉ gửi qua disposable env/fixture an toàn, không ghi credential thật.

---

## 8. Handoff cho owner khác

### BE-1

- Customer identity lookup interface.
- Customer ID ownership semantics.
- Staff role assumptions.
- Shared controller change plan.

### BE-3

- `AccountModuleApi` usage.
- Account pair lock contract.
- Status/currency/balance validation.
- Block-vs-confirm setup.
- Account metric query.

### BE-4

- Seed/block audit facts.
- DB schema/index review.
- Pool/lock metric commands.
- Acceptance evidence.

### FE/BE

- Lookup/seed/block browser steps.
- Masked synthetic response example.
- Expected 403/404/409/429.
- Query refresh/invalidation behavior.

---

## 9. Definition of Done

- Focused tests pass.
- PostgreSQL evidence recorded.
- DB metric handoff complete.
- FE Operator E2E runs from instructions.
- Shared controller owner resolved.
- No migration edited in place.
- No commit/push without explicit instruction.
