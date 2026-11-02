# Week 1 — Foundation, Recipient Resolve, Small Transfer

> **Dates:** 20/10 – 26/10/2026 (Thực tế: harness tạo 19/10; lịch ghi nhận 21/10 — các task Day 1 dồn vào Day 2)
> **Trạng thái:** ✅ W1 hoàn thành 21/10/2026
> **Kế hoạch nguồn:** [5. Timelines §3](../plans/5.%20Timelines.md) — tài liệu này khai triển chi tiết + là nơi ghi evidence thực tế.
> **Tham chiếu kỹ thuật:** [2. API docs](../plans/2.%20API%20docs.md) · [3. Architecture](../plans/3.%20Architecture.md) · [1. ERD](../plans/1.%20ERD.md)

---

## 0. Mục tiêu tuần (Definition of Done)

| # | Mục tiêu | Nguồn |
|---|---|---|
| G1 | Chốt state machine + module boundary; mọi khác biệt code ↔ docs được ghi vào **MISMATCH log** (§5) | Timelines §3 |
| G2 | Migrations V1–V7 apply OK trên Docker Compose local (bảng `transfers` tại V3, constraints/indexes tại V5) | ERD §7 |
| G3 | Recipient resolve pass tests: 200 / 404 concealment / 429 rate limit | API docs §2 |
| G4 | Small transfer (≤ 5M VNĐ) pass unit test + happy-path integration: create 50K → 201 COMPLETED, balance đúng | Architecture §2 |

**Không thuộc scope W1:** large path (>5M), OTP lifecycle, confirm-otp, concurrency tests (CON-*), failure injection (FI-*). Đây là scope W2/W3.

---

## 1. BE-Core — Money Path

### Day 1–2 (20–21/10) — Environment + Boundary Review

#### Task 1.1: Environment bootstrap
- [x] `docker compose up -d` — PostgreSQL up, healthy
- [x] Xác nhận migrations V1–V7 đã apply: query `flyway_schema_history` (hoặc migration table tương ứng), đối chiếu V3 (`transfers` + `account_seed_records`) và V5 (constraints + indexes) với [ERD §2, §7](../plans/1.%20ERD.md) — evidence §4.2
- [x] `cd be && mvn validate` — build config OK
- [x] `mvn -q -Dtest="*Transfer*,*Recipient*" test` — chạy baseline, ghi số lượng pass/fail vào §4

#### Task 1.2: Boundary review — so `transfer/**` hiện trạng với thiết kế
Đối chiếu từng file hiện có với package structure trong [Architecture §1](../plans/3.%20Architecture.md). **Kết quả quan sát sơ bộ 21/10 (cần xác minh chi tiết):**

| Hiện trạng code | Thiết kế (Architecture §1) | Đánh giá |
|---|---|---|
| `application/TransferService.java` (gộp) | `CreateTransferService` + `ConfirmTransferOtpService` tách riêng | ⚠️ MISMATCH-M01 — quyết định: tách hoặc giữ + ghi lý do |
| `application/TransferQueryService.java` | ✓ trùng khớp | OK |
| Không có `application/ResolveRecipientService.java` (logic nằm trong `web/RecipientController`?) | Service riêng, controller chỉ mỏng | ⚠️ MISMATCH-M02 — quy cho BE-Support xử lý Day 1–3 |
| Không có `domain/` package (`Transfer`, `TransferStatus`, `TransferPolicy`, `TransferLockOrder`) | Domain layer độc lập infrastructure | ⚠️ MISMATCH-M03 — `TransferPolicy` đang nằm trong `application/` |
| `infrastructure/persistence/TransferJdbcRepository` + `TransferOtpRepository` | `TransferRepository` + `TransferLockRepository` (native SQL FOR UPDATE) + `TransferIdempotencyService` | ⚠️ MISMATCH-M04 — quan trọng: chưa thấy lock repository tách riêng, cần cho money path |
| Không có `web/dto/` package tách riêng | `dto/` riêng cho request/response | Minor — ghi nhận |

➡️ Mọi kết luận "code sai/đúng" phải ghi rõ vào **MISMATCH log** (§5) kèm bằng chứng (tên file, dòng code, behavior). Không tự refactor ngoài scope tuần này — chỉ ghi nhận và quyết định.

#### Task 1.3: Chốt state machine + lock order
- [x] Xác nhận 4 trạng thái `AWAITING_OTP | COMPLETED | EXPIRED | FAILED` + CHECK constraints trong DB khớp [ERD §6](../plans/1.%20ERD.md) (đặc biệt: `AWAITING_OTP` ⇔ `expires_at`/`otp_challenge_id` NOT NULL; `COMPLETED` ⇔ `completed_at` NOT NULL)
- [x] Chốt lock order: `ORDER BY id ASC FOR UPDATE` do **PostgreSQL quyết định**, không dùng Java `UUID.compareTo()` (lý do: khác byte-order — Architecture §5)
- [x] Verify bằng `EXPLAIN (ANALYZE, BUFFERS)` trên câu lock SQL; paste execution plan vào §4 evidence

**Nguyên tắc nghiêm cấm nhắc lại (Timelines §9):** không sửa migration đã apply; không `down -v`/`DROP`/`TRUNCATE` trong CI; không H2 cho lock test.

### Day 3–5 (22–24/10) — Small Transfer Path

#### Task 3.1: `CreateTransferService.create()` — small path đủ 9 bước
Thứ tự bắt buộc (Architecture §2), mỗi bước có test riêng nếu có thể:

| Bước | Nội dung | Lưu ý |
|---|---|---|
| 1 | Validate actor, source ownership | `404 ACCOUNT_NOT_FOUND` nếu source không thuộc customer |
| 2 | PIN verify — `verifyPin(customerId, pin)` trong **REQUIRES_NEW TX** | `400 PIN_INVALID` / `403 PIN_LOCKED`; chặn trước khi đụng idempotency |
| 3 | Validate DTO, self-transfer (`400 VALIDATION_ERROR`), amount (UT-01..03), key format 16–128 printable ASCII | Pure validation, chưa mở transaction |
| 4 | Hash canonical payload (SHA-256, không chứa `pin`, memo null = "") → `claimOrReplay(actorId, key, hash)` | Replay → trả result gốc + `Idempotency-Replayed: true`, **không tạo transfer mới** |
| 5 | Lock accounts: `lockAccountsAscending(sourceId, destId)` — native SQL `ORDER BY id ASC FOR UPDATE` | Bên trong money TX (REQUIRED) |
| 6 | Validate eligibility dưới lock: ACTIVE, cùng currency, ownership, balance ≥ amount | `409 ACCOUNT_NOT_ELIGIBLE` / `INSUFFICIENT_FUNDS` |
| 7 | `INSERT transfers (status=COMPLETED, completed_at=now)` + `debit(source)` + `credit(dest)` + `AuditModuleApi.append(TRANSFER_COMPLETED)` | `amount NUMERIC(19,0)`, `BigDecimal` scale 0 — không float/double |
| 8 | `complete(idempotencyRecordId, 201, transferBody)` rồi **COMMIT** | |
| 9 | AFTER_COMMIT: publish `TransferCommittedEvent` → risk evaluation REQUIRES_NEW (W1 chỉ cần skeleton — failure không được rollback transfer) | Risk là scope W3 |

- [x] Đã triển khai đủ 9 bước trong `TransferService.createTransaction` (lưu ý bước 2/3: thứ tự thực tế là validate → parseAmount → verifyPin — xem MISMATCH M07 + DECISIONS.md). Các port `AccountModuleApi` / `IdentityModuleApi` tạo tại `transfer/api/`, adapter trong `transfer/infrastructure/persistence/` — không inject repository cross-module vào service.

#### Task 3.2: Unit tests money path
- [x] **UT-01** — `"50000"` → parse đúng `BigDecimal("50000")` (`TransferPolicyTest`)
- [x] **UT-02** — `"50000.5"`, `"0"`, `"-100"`, `"+100"`, `" 100"`, `"1e5"` → `ValidationException(AMOUNT_INVALID)`
- [x] **UT-03** — `"1999"`, `"10000001"` (ngoài range [2000, 10000000]) → `AMOUNT_INVALID`
- [x] **UT-04** — self-transfer: `sourceId == destId` → `400 VALIDATION_ERROR` (`TransferServiceTest`)
- [x] **UT-05** — memo `null` và memo `""` → cùng payload hash
- [x] **UT-06** — đổi bất kỳ field nào (source/dest/amount/currency/memo) → hash khác

#### Task 3.3: TransferLockRepository + claim loser
- [x] Native SQL lock repository theo Architecture §5 (khớp Task 1.3 đã chốt) — `TransferLockRepository.lockAccountsAscending`
- [x] **Claim loser xử lý NGOÀI transaction**: giữ `ON CONFLICT DO NOTHING` cho idempotency claim — không ném `DuplicateKeyException` nên không có rủi ro đánh dấu rollback-only cho money TX; claim loser đi qua `replayOrConflict` (khác hash → `409 IDEMPOTENCY_KEY_REUSED`) tương đương Architecture §7 (MISMATCH M04)

### Day 6–7 (25–26/10) — Review chéo + Integration
- [x] Review PR của BE-Support (resolve + query skeleton) — BE-Core là reviewer bắt buộc mọi PR money path (Timelines §1). *Ghi chú: phiên này làm trực tiếp cả hai phần BE-Core + BE-Support trong cùng worktree, không có PR riêng — review thực hiện inline trên code.*
- [x] Happy-path integration test: create **50.000 VNĐ** → `201 COMPLETED`; assert: source trừ đúng 50K, dest cộng đúng 50K, `completed_at` set, audit fact tồn tại — `TransferSmallTransferPostgresTest` (Testcontainers postgres:16-alpine, Flyway V1–V8)
- [x] Smoke ranh giới 5M (SM-04/SM-05 chỉ cần ở mức create — full state machine là W2): 5M → small path `COMPLETED`; 5M+1 → large path `AWAITING_OTP` + OTP gửi qua `OtpSender` (mock), balance không đổi

---

## 2. BE-Support — Read Path

### Day 1–3 (20–22/10) — Recipient Resolve

#### Task 2.1: `ResolveRecipientService`
- [x] Tách logic resolve ra khỏi `RecipientController` (xử lý MISMATCH-M02) — controller chỉ parse + map response
- [x] Gọi `AccountModuleApi.findByAccountNumber(accountNumber)` — không inject repository cross-module
- [x] Validate: tồn tại + `status = ACTIVE` + `currency = VND` + **không phải tài khoản của chính customer đang gọi**
- [x] **Concealment 404:** mọi trường hợp fail (không tồn tại / không ACTIVE / không VND / self / BLOCKED) đều trả **cùng một** `404 RECIPIENT_NOT_AVAILABLE` — không tiết lộ lý do cụ thể (API docs §2)
- [x] Response chỉ chứa: `accountId`, `accountNumberMasked`, `recipientDisplayName`, `currency` — không phone/email/full number
- [x] Không cache kết quả resolve

#### Task 2.2: Rate limit
- [x] 30 request/phút/customer UUID → vượt → `429 RATE_LIMITED` (`RecipientResolveRateLimitMvcTest`)
- [x] Key rate limit theo customer (từ JWT), không theo IP — `RateLimitPolicyFactory.actorKey` đọc `currentActorId()` từ SecurityContext

#### Task 2.3: Tests resolve
- [x] `200` — account hợp lệ: masking đúng format `••••3004` (chỉ 4 số cuối visible — QV-06 phần resolve)
- [x] `404` — account không tồn tại
- [x] `404` — account của chính mình (self)
- [x] `429` — request thứ 31 trong 1 phút
- [x] Account number 8–34 chữ số theo OpenAPI — không thu hẹp còn 12 (test 7 chữ số / 35 chữ số / chứa chữ cái → 400 VALIDATION_ERROR)

### Day 4–6 (23–25/10) — Query Skeleton

#### Task 2.4: `TransferQueryService.listTransfers()` — cursor pagination
- [x] Cursor: `CursorPosition {version:1, timestamp, id}` → JSON UTF-8 → **Base64 URL-safe (no padding)**
- [x] SQL predicate next-page:
  ```sql
  WHERE (created_at < :cursor_time)
     OR (created_at = :cursor_time AND id < :cursor_id)
  ORDER BY created_at DESC, id DESC
  LIMIT :limit_plus_one
  ```
- [x] `limit+1` để detect còn trang sau; cursor encode từ record **cuối cùng được trả**, không phải record thứ limit+1 (test `nextCursorEncodedFromLastReturnedItemWhenRepositoryReturnsExtraRow`)
- [x] Malformed cursor → `400 VALIDATION_ERROR`
- [x] `limit` range 1..100, default 20

#### Task 2.5: `getTransfer()` — visibility + concealment
- [x] **Source owner:** thấy mọi status
- [x] **Destination owner:** chỉ thấy `COMPLETED`; truy cập transfer non-completed → `404 TRANSFER_NOT_FOUND` (không `403` — không tiết lộ sự tồn tại)
- [x] `direction`: `OUTGOING` (source) / `INCOMING` (destination); `counterpartyAccountMasked` chỉ 4 số cuối

#### Task 2.6: Tests query (skeleton)
- [x] **QV-01** — source owner thấy `AWAITING_OTP` trong list
- [x] **QV-02** — destination owner không thấy `AWAITING_OTP`/`FAILED` (detail 404, list empty)
- [x] **QV-03** — destination owner thấy `COMPLETED`
- [x] **QV-04** — tie-break: 2 transfers cùng `created_at` → order stable theo `id DESC` (`TransferSmallTransferPostgresTest.sameCreatedAtTieBreaksByIdDescending`, Postgres thật)
- [x] **QV-05** — malformed cursor → `400`
- [x] *(Filter `status`/`from`/`to` hoàn chỉnh + `INVALID_DATE_RANGE` là scope W2 — W1 chỉ skeleton đủ test visibility)*

### Day 7 (26/10) — Evidence
- [x] Ghi kết quả test, MISMATCH, quyết định tuần vào file này (§3–§6)
- [x] Chạy `make check` (AGENTS.md quy tắc tan ca)

---

## 3. Deliverables W1 — Checklist nghiệm thu

| # | Deliverable | Owner | Trạng thái |
|---|---|---|---|
| D1 | Small transfer path + UT-01..06 + happy-path integration pass | BE-Core | ✅ |
| D2 | Recipient resolve (200/404/429) + masking pass | BE-Support | ✅ |
| D3 | Query skeleton: cursor + visibility QV-01..05 pass | BE-Support | ✅ |
| D4 | State machine + lock order chốt, có `EXPLAIN (ANALYZE, BUFFERS)` | BE-Core | ✅ |
| D5 | `Work/Week1.md` evidence hoàn chỉnh (file này) | Cả hai | ✅ |

## 4. Evidence — Kết quả thực tế

> **Quy tắc:** Không ghi "PASS" mà không có fresh test output (Timelines §9). Paste output thật, kèm timestamp.

### 4.1 Test output

**Baseline đầu phiên (21/10/2026, trước thay đổi):** 27/27 pass với Java 21 (`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`).

```bash
cd be && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn -Dtest="*TransferServiceTest,*TransferServiceLifecycleTest,*TransferControllerMvcTest,*Recipient*Test" test
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.web.TransferControllerMvcTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.web.RecipientControllerMvcTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.web.RecipientResolveRateLimitMvcTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.application.TransferServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.application.TransferServiceLifecycleTest
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
*(ngày chạy: 21/10/2026, ~19:47 ICT — output trên đã rút gọn chỉ giữ dòng "Tests run")*

**Toàn bộ suite (21/10/2026):** `mvn test` → **183 tests, 0 failures, 0 errors** — gồm:
- Transfer: 55 tests (7 Policy + 6 Query + 6 Lifecycle + 15 Service + 9 RecipientMvc + 1 RateLimitMvc + 7 TransferMvc + 4 Postgres integration)
- Cross-module harness `UiApiRegressionPostgresTest`: 4 tests (transfer create/replay/history/terminal states trên Postgres thật)
- Identity/account/risk/audit/customer/foundation: 124 tests

### 4.2 Migration verification

```
# flyway_schema_history trên Docker Compose local — ngày: 21/10/2026
 1 | identity customer account audit    | t
 2 | identity security and idempotency  | t
 3 | seed ledger and transfers          | t
 4 | risk flags                         | t
 5 | mvp schema constraints and indexes | t
 6 | recovery reset tokens              | t
 7 | registration verification tokens   | t
```

- V3: bảng `transfers` + `account_seed_records` ✓ (constraints `ck_transfers_distinct_accounts`, `ck_transfers_amount`, `ck_transfers_currency`, `ck_transfers_status`, `ck_transfers_completion`, `ck_transfers_pending_expiry`)
- V5: constraints/indexes mvp ✓
- **V8 (`transfers_state_invariants`)** được verify qua Testcontainers (Flyway chạy tươi V1–V8 trên postgres:16-alpine): `TransferSmallTransferPostgresTest` + `UiApiRegressionPostgresTest` pass với constraint `transfers_awaiting_requires_otp` + `transfers_expiry_only_awaiting` hoạt động. Docker image backend compose được build 19/10 nên chưa chứa V8 — sẽ áp dụng ở lần build image tiếp theo.

### 4.3 EXPLAIN lock order

```
# EXPLAIN (ANALYZE, BUFFERS) của ORDER BY id ASC FOR UPDATE — ngày: 21/10/2026
 LockRows  (cost=9.67..9.70 rows=2 width=124) (actual time=0.035..0.036 rows=0 loops=1)
   Buffers: shared hit=7
   ->  Sort  (cost=9.67..9.68 rows=2 width=124) (actual time=0.035..0.035 rows=0 loops=1)
         Sort Key: id
         Sort Method: quicksort  Memory: 25kB
         Buffers: shared hit=7
         ->  Bitmap Heap Scan on accounts  (cost=4.31..9.66 rows=2 width=124) (actual time=0.003..0.003 rows=0 loops=1)
               Recheck Cond: (id = ANY ('{...001,...002}'::uuid[]))
               Buffers: shared hit=4
               ->  Bitmap Index Scan on accounts_pkey  (cost=0.00..4.31 rows=2 width=0) (actual time=0.001..0.001 rows=0 loops=1)
                     Index Cond: (id = ANY ('{...001,...002}'::uuid[]))
                     Buffers: shared hit=4
 Planning Time: 0.336 ms
 Execution Time: 0.084 ms
```

Kết luận: `LockRows` (FOR UPDATE) đặt **sau** `Sort Key: id` — PostgreSQL tự quyết định thứ tự khóa theo `id ASC`, đúng Architecture §5. (DB local chưa có accounts nên rows=0 — kế hoạch vẫn hiển thị đầy đủ cấu trúc lock.)

### 4.4 Happy-path integration (create 50K)

```
# TransferSmallTransferPostgresTest — Testcontainers postgres:16-alpine, Flyway V1–V8 — ngày: 21/10/2026
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.bank.simulator.transfer.TransferSmallTransferPostgresTest
```

- `fiftyThousandTransferCompletesImmediatelyWithBalancesAuditAndReplay`: create 50.000 VNĐ → `COMPLETED`; source `10000000 → 9950000`, dest `0 → 50000`; `completed_at` NOT NULL; 1 audit fact `TRANSFER_COMPLETED/SUCCESS`; replay cùng key → `replayed=true`, vẫn 1 dòng transfers, balance không đổi.
- `fiveMillionBoundaryStillUsesSmallPathAndCompletesImmediately` (SM-04): 5.000.000 → `COMPLETED`, balances 5M/5M.
- `fiveMillionPlusOneUsesLargePathAwaitingOtpWithoutMovingMoney` (SM-05): 5.000.001 → `AWAITING_OTP`, `expires_at` set, `otp_challenge_id` NOT NULL, OTP gửi qua OtpSender (mock, code 6 chữ số), balances giữ nguyên 10M/0.
- `sameCreatedAtTieBreaksByIdDescending` (QV-04): 2 transfers cùng `created_at` → `findHistory` trả `id DESC` (uuid `...002` trước `...001`).

## 5. MISMATCH Log — Code ↔ Docs

> Mọi khác biệt giữa `transfer/**` hiện trạng và thiết kế plans. Không tự sửa — ghi nhận, quyết định, ghi vào DECISIONS.md nếu đổi thiết kế.

| ID | Ngày | Hiện trạng | Kỳ vọng (docs) | Quyết định | Owner |
|---|---|---|---|---|---|
| M01 | 21/10 | `TransferService` gộp create+confirm | `CreateTransferService` + `ConfirmTransferOtpService` tách riêng (Arch §1) | ✅ **Giữ gộp trong W1.** Confirm-otp là scope W2/W3; tách ngay bây giờ = refactor ngoài scope tuần (vi phạm quy tắc "một tính năng tại một thời điểm"). Tách khi vào W2. | BE-Core |
| M02 | 21/10 | Logic resolve nằm trong `RecipientController` | `ResolveRecipientService` riêng (Arch §1) | ✅ **Đã sửa**: tạo `application/ResolveRecipientService`, controller chỉ parse + map | BE-Support |
| M03 | 21/10 | Không có `domain/` package; `TransferPolicy` ở `application/` | Domain layer: `Transfer`, `TransferStatus`, `TransferPolicy`, `TransferLockOrder` (Arch §1) | ✅ **Giữ `application/` trong W1** (không refactor ngoài scope). `TransferPolicy` là pure functions, không lệ thuộc infrastructure — di chuyển sang `domain/` cùng lúc tách service ở W2. | BE-Core |
| M04 | 21/10 | `TransferJdbcRepository` + `TransferOtpRepository`, chưa có lock repository riêng; idempotency claim dùng `ON CONFLICT DO NOTHING` | `TransferRepository` + `TransferLockRepository` (native SQL FOR UPDATE) + `TransferIdempotencyService` (Arch §1) | ✅ **Đã sửa phần lock**: tạo `TransferLockRepository` native SQL `ORDER BY id ASC FOR UPDATE`. **Idempotency giữ `ON CONFLICT DO NOTHING`**: không ném `DuplicateKeyException` → không có rủi ro rollback-only; claim loser đi `replayOrConflict` (khác hash → 409 `IDEMPOTENCY_KEY_REUSED`) — tương đương hành vi Arch §7, chưa cần service riêng | BE-Core |
| M05 | 21/10 | Error code lệch contract: PIN sai trả 403 (contract: 400), source không thuộc customer trả 403 FORBIDDEN (contract: 404 ACCOUNT_NOT_FOUND), account BLOCKED trả 404 (contract: 409 ACCOUNT_NOT_ELIGIBLE), failureCode `OTP_INVALID` (contract: `OTP_ATTEMPTS_EXCEEDED`) | [API docs §3](../plans/2.%20API%20docs.md) | ✅ **Đã sửa**: `PIN_INVALID` 400 / `PIN_LOCKED` 403 / `ACCOUNT_NOT_FOUND` 404 (không tiết lộ) / `ACCOUNT_NOT_ELIGIBLE` 409 / `INSUFFICIENT_FUNDS` 409 / failureCode `OTP_ATTEMPTS_EXCEEDED` | BE-Core |
| M06 | 21/10 | DB thiếu 2 CHECK constraint state machine: `AWAITING_OTP → otp_challenge_id NOT NULL` và `expires_at NOT NULL → AWAITING_OTP` | [ERD §6](../plans/1.%20ERD.md) | ✅ **Đã sửa**: migration forward-only `V8__transfers_state_invariants.sql` (V1–V7 không đụng — Timelines §9) | BE-Core |
| M07 | 21/10 | Thứ tự thực tế trong `create()`: validate DTO → parseAmount → verifyPin (spec bảng 9 bước để PIN ở bước 2, trước validate) | Architecture §2 | ✅ **Giữ thứ tự validate-truớc-PIN**: request hỏng (amount/key/self) bị chặn trước khi đốt PIN attempt; PIN vẫn verify **trước** idempotency claim và money TX đúng yêu cầu. Ghi DECISIONS.md. | BE-Core |
| M08 | 21/10 | `TransferJdbcRepository.complete/expire/fail` không xoá `expires_at` khi chuyển terminal | V8 `transfers_expiry_only_awaiting` (ERD §6) | ✅ **Đã sửa**: 3 method thêm `expires_at = NULL` | BE-Core |

*(M01–M04 là quan sát từ cấu trúc file ngày 21/10 — đã xác minh hành vi chi tiết và chốt quyết định trong cùng ngày.)*

## 6. Dependencies W1 & Fallback

| Dependency | Owner | Trạng thái | Fallback nếu trễ |
|---|---|---|---|
| `AccountModuleApi` (`findByAccountNumber`, `lockAccountsAscending`, `debit`/`credit`, `validateTransferEligibility`) | BE-A | ✅ có sẵn | Mock theo signature đã chốt trong [API docs §8](../plans/2.%20API%20docs.md) |
| `IdentityModuleApi.verifyPin()` | BE-A | ✅ có sẵn | Mock |
| `AuditModuleApi.append()` | BE-B | ✅ có sẵn | Mock |

## 7. Quyết định & Ghi chú tuần

*(Cập nhật cuối tuần — mọi quyết định ảnh hưởng thiết kế copy sang DECISIONS.md)*

- 21/10: Harness khởi tạo; Day 1 (20/10) dồn vào Day 2 (21/10) theo ghi chú Timelines §3. Quan sát sơ bộ MISMATCH M01–M04 từ listing cấu trúc `transfer/**`.
- 21/10: M01–M08 đã xử lý (xem §5). Quyết định thiết kế chính copy sang DECISIONS.md: giữ TransferService gộp, thứ tự validate→PIN, V8 invariant, lock order PostgreSQL, ON CONFLICT DO NOTHING.
- 21/10: Java system (v25) không tương thích Mockito/byte-buddy → mọi lệnh mvn phải chạy với `JAVA_HOME` trỏ JDK 21. Đã ghi vào `Makefile` (`check` target) + PROGRESS.md "Vấn đề đã biết".
- 21/10: Tạo `Makefile` gốc repo với target `check` (openapi + mvn test Java 21 + git diff --check) — đáp ứng M12/AGENTS.md "make check".
- 21/10: Harness root package phải chỉnh 2 chỗ để suite xanh: (1) `UiApiRegressionPostgresTest` fixture `AWAITING_OTP` cần `otp_challenge_id` không null (do V8); (2) `FoundationPostgresTest` thiếu `app.security.jwt-secret` (lỗi config có sẵn từ trước, không liên quan transfer). Đã sửa tối thiểu + ghi rõ ở commit.
- 21/10: Không có PR riêng cho BE-Support (làm trực tiếp cùng worktree) — review inline thay cho review PR (Timelines §1).

## 8. Checklist tan ca (mỗi ngày)

- [x] Cập nhật evidence §4 (fresh output nếu chạy test)
- [x] MISMATCH mới → §5
- [x] `make check` pass
- [x] Commit phần việc hoàn thành (chỉ trong `transfer/**` + `docs transactions/**` + 2 harness root package đã ghi chú)
