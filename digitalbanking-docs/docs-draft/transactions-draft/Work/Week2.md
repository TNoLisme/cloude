# Week 2 — Large Transfer, OTP Lifecycle, Concurrency

> **Kế hoạch gốc:** 27/10 – 02/11/2026 · **Thực tế:** W2 bắt đầu **01/11/2026** (trễ 5 ngày — tuần trước nghiêng về hoàn thiện evidence W1 + harness `make check`; xem §7). Giữ nguyên khối lượng 7 ngày: **01/11 – 07/11**; W3 dự kiến dịch sang **08/11 – 14/11**.
> **Trạng thái:** ✅ **Hoàn thành 01/11/2026** — toàn bộ task + deliverable D1–D5 pass, `make check` xanh **215 tests / 0 fail** (§4).
> **Kế hoạch nguồn:** [5. Timelines §4](../plans/5.%20Timelines.md) — tài liệu này khai triển chi tiết + là nơi ghi evidence thực tế.
> **Tham chiếu kỹ thuật:** [2. API docs](../plans/2.%20API%20docs.md) §3–§6 · [3. Architecture](../plans/3.%20Architecture.md) §3–§8, §10 · [1. ERD](../plans/1.%20ERD.md) §4, §6 · [7. Advanced Business](../plans/7.%20Advanced%20Business.md) (AC-01..07, AF-01..04, AT-01..03)
> **Baseline đầu tuần:** 183 tests / 0 fail (Java 21, `make check` pass 01/11/2026) → **cuối tuần: 215 tests / 0 fail**

---

## 0. Mục tiêu tuần (Definition of Done)

| # | Mục tiêu | Nguồn |
|---|---|---|
| G1 | Large transfer (>5M) đủ vòng đời: AWAITING_OTP → dispatch → confirm → terminal, đúng state machine SM-01..05 | Timelines §4, ERD §6 |
| G2 | Concurrency CON-01..03 + FI-01 pass với **PostgreSQL thật** (Testcontainers, không H2) | Architecture §10 |
| G3 | Expiry lazy evaluation (AT-01..03) + history filter `status`/`from`/`to` hoàn chỉnh (QV-01..06 full) | Architecture §8, API docs §5 |
| G4 | Giải quyết MISMATCH tồn từ W1: tách `CreateTransferService`/`ConfirmTransferOtpService` (M01), `TransferPolicy` → `domain/` (M03), module boundary OTP (M09–M11, §5) | Week1 §5, Arch §1 |

**Không thuộc scope W2:** CON-04/05 (duplicate-confirm race, block-vs-confirm — cần phối hợp BE-A), FI-02..05 (timeout-after-commit, restart, compensation race đầy đủ), risk rules hoàn chỉnh, load test — tất cả là W3. Đây là cơ sở để nếu trượt lịch thì trượt lịch, **không** cắt scope concurrency (Timelines §9).

---

## 1. BE-Core — Confirm State Machine + Concurrency

### Day 1–2 (01–02/11) — Refactor boundary + Large Create + Compensation

#### Task 1.1: Tách service (giải quyết M01 + M03) — **điều kiện tiên quyết**
- [x] Tách `TransferService` (đang gộp create + confirm) thành `CreateTransferService` + `ConfirmTransferOtpService` theo [Architecture §1](../plans/3.%20Architecture.md)
- [x] Di chuyển `TransferPolicy` (+ thêm `TransferStatus` nếu cần) sang package `transfer/domain/` — domain không phụ thuộc infrastructure
- [x] **Ràng buộc: refactor thuần cơ học, không đổi behavior** — sau refactor toàn bộ suite W1 phải vẫn xanh (183 tests) trước khi viết code W2 mới

#### Task 1.2: Module boundary OTP (giải quyết M09 + M10 + M11 — xem §5)
- [x] Mở rộng port `transfer/api/IdentityModuleApi` theo [API docs §8](../plans/2.%20API%20docs.md):
  - `UUID issueOtp(UUID customerId, String purpose)` — purpose `TRANSFER_STEP_UP`
  - `OtpConsumeResult consumeOtp(UUID challengeId, String otp)` — trả đủ outcome: VALID / INVALID(attempts) / EXPIRED / INVALIDATED
- [x] Adapter `IdentityModuleApiAdapter` (đã có sẵn trong `transfer/infrastructure/persistence/`) được mở rộng gọi identity internals — service layer **không inject** `OtpChallengeRepository`/`OtpHashingService`/`OtpSender` trực tiếp
- [x] `TransferOtpRepository` hiện đang read/write bảng `otp_challenges` của module identity (vi phạm ERD §4) → logic chuyển hết vào adapter hoặc port riêng của transfer; không đổi schema `otp_challenges`
- [x] **Không sửa `identity/**`** — nếu `OtpChallengeService` thiếu API cần thiết: ghi MISMATCH + proposal cho BE-A (AGENTS.md ràng buộc)
- [x] Quyết định thiết kế chốt ở đây → copy sang DECISIONS.md (quy tắc tan ca)

#### Task 1.3: Large path create đủ 7 bước ([Architecture §3](../plans/3.%20Architecture.md))
Hiện trạng 01/11: large path create **đã chạy được** (smoke SM-05 pass ở W1, `OtpSender` mock) — task này là verify từng bước theo spec + bọc lại qua port sau Task 1.2:

| Bước | Nội dung | Lưu ý |
|---|---|---|
| 1 | PIN verify `REQUIRES_NEW` — như small path | `400 PIN_INVALID` / `403 PIN_LOCKED`, chặn trước idempotency |
| 2 | Money TX (REQUIRED): claim idempotency `ON CONFLICT DO NOTHING` | Giữ nguyên pattern W1 (DECISIONS.md) |
| 3 | `issueOtp(customerId, TRANSFER_STEP_UP)` trong money TX | `otp_challenges.expires_at = now + 120s` |
| 4 | `INSERT transfers (status=AWAITING_OTP, expires_at, otp_challenge_id)` | Phải thỏa 2 CHECK constraint V8 |
| 5 | `complete(idempotencyRecordId, 200, awaitingBody)` → **COMMIT** | Response body theo API docs §3 Case B: `expiresAt`, `expiresInSeconds=120`, `message` |
| 6 | **Sau COMMIT**: dispatch OTP qua adapter `OtpSender` | Tuyệt đối không dispatch trong TX (Arch §12 — OTP gửi dù transfer rollback) |
| 7 | Dispatch fail/timeout → **compensation TX (REQUIRES_NEW)**: lock transfer + challenge → `UPDATE ... SET status='FAILED', failure_code='OTP_DISPATCH_FAILED' WHERE id=:id AND status='AWAITING_OTP'` + invalidate challenge → trả `503 SERVICE_UNAVAILABLE` | Điều kiện `WHERE status='AWAITING_OTP'` = terminal không bao giờ bị ghi đè (AF-04) |

- [x] Tests create large path:
  - `200 AWAITING_OTP`: `expires_at` set, `otp_challenge_id` NOT NULL, **balances không đổi**, `expiresInSeconds=120`
  - Dispatch fail → `503` + transfer `FAILED(OTP_DISPATCH_FAILED)` + challenge invalidated (AF-03)
  - Replay cùng key khi đang `AWAITING_OTP` → current state + TTL còn lại, **không gửi OTP mới** (API docs §9)

### Day 3–4 (03–04/11) — Confirm OTP State Machine

#### Task 3.1: `ConfirmTransferOtpService.confirm()` — đủ 8 bước ([Architecture §4](../plans/3.%20Architecture.md))
Hiện trạng 01/11: `TransferService.confirm()` đã có (code trước harness) nhưng vi phạm boundary M09/M10 — Task 1.2 là điều kiện trước. Đối chiếu từng bước:

| Bước | Nội dung | Lưu ý |
|---|---|---|
| 1 | Lock transfer row `FOR UPDATE`; verify ownership: actor ≠ source owner → `404 TRANSFER_NOT_FOUND` | Concealment — destination owner cũng nhận 404 (API docs §4) |
| 2 | Đã `COMPLETED` → replay `200` + `Idempotency-Replayed: true`, không đụng OTP | Idempotent theo `transferId`, không cần Idempotency-Key |
| 3 | Đã `FAILED` → `409 STATE_CONFLICT` · đã `EXPIRED` hoặc `now > expires_at` → `409 TRANSFER_EXPIRED`, không mutation | SM-02/SM-03 + AT-01 — server là thẩm quyền duy nhất về expires_at |
| 4 | Lock OTP challenge row theo transfer | **Lock order: transfer → challenge → accounts ASC** (không deadlock với create path) |
| 5 | `consumeOtp` sai (attempt 1–4): increment attempts + **COMMIT** → `400 OTP_INVALID`, status vẫn `AWAITING_OTP` | Attempt phải được persist dù response là lỗi |
| 6 | Sai lần 5: attempts=5 + invalidate challenge + transfer → `FAILED(OTP_ATTEMPTS_EXCEEDED)` — tất cả **cùng 1 TX** → COMMIT → `409 STATE_CONFLICT` | Các lần gọi sau trên transfer FAILED cũng `409 STATE_CONFLICT` |
| 7 | OTP valid → **re-validate dưới lock**: ACTIVE, cùng currency, ownership, balance ≥ amount — fail → transfer `FAILED(INSUFFICIENT_FUNDS \| ACCOUNT_NOT_ELIGIBLE)` + COMMIT + `409` | Không đổi balance khi re-validation fail |
| 8 | Pass → `UPDATE transfers SET status='COMPLETED', completed_at=now` + debit + credit + `AuditModuleApi.append(TRANSFER_COMPLETED)` → COMMIT → `201 COMPLETED` | AFTER_COMMIT: risk event (skeleton đã có từ W1) |

#### Task 3.2: Tách transaction result khỏi HTTP exception mapping
- [x] Service trả **outcome object** (commit xong) — web layer map sang 400/409 **sau khi** transaction đã commit. Không ném exception từ trong TX đang chạy khi attempt/failure vẫn cần persist (Arch §4 "Critical design decision", pairing AC-06)
- [x] Verify không có lối nào rollback sau khi đã ghi attempt/failure_code

#### Task 3.3: Tests state machine + OTP attempts
- [x] **SM-01** — terminal COMPLETED: cố UPDATE sang status khác → fail, không mutation
- [x] **SM-02** — terminal FAILED → confirm → `409 STATE_CONFLICT`, không mutation
- [x] **SM-03** — terminal EXPIRED → confirm → `409 TRANSFER_EXPIRED`, không mutation
- [x] **SM-04/SM-05** — re-run sau refactor (5M → COMPLETED small path; 5M+1 → AWAITING_OTP large path)
- [x] Wrong-OTP 1..4 → `400 OTP_INVALID`, attempts tăng dần, status vẫn `AWAITING_OTP`
- [x] Wrong-OTP lần 5 → transfer `FAILED(OTP_ATTEMPTS_EXCEEDED)` + `409 STATE_CONFLICT`; gọi lần 6+ → vẫn `409 STATE_CONFLICT`
- [x] **AT-01** expiry boundary: confirm tại ngay trước/ngay sau `expires_at` → `409 TRANSFER_EXPIRED` — dùng `Clock` injectable, **không sleep** (Timelines §9)
- [x] Replay confirm sau COMPLETED → `200` + `Idempotency-Replayed: true`, không trừ tiền lần 2

### Day 5–6 (05–06/11) — Concurrency Tests + FI-01

#### Task 5.1: Harness pattern concurrency
- [x] `CountDownLatch` + `ExecutorService` + `Future.get(timeout)` — **nghiêm cấm sleep-sync** (Timelines §9)
- [x] `Clock` bean injectable cho mọi test liên quan thời gian
- [x] Testcontainers postgres:16-alpine (như W1) — **không H2** cho lock test

#### Task 5.2: CON-01 — Overdraft prevention (AC-01)
- [x] Setup: source 1.000.000 VNĐ; 2 transfer 600.000 VNĐ concurrent đến 2 dest khác nhau
- [x] Assert: 1× `201 COMPLETED` + 1× `409 INSUFFICIENT_FUNDS`; source balance = 400.000; dest +600.000; **không bao giờ âm**

#### Task 5.3: CON-02 — Same-key idempotency race (AC-02 + AC-07)
- [x] N request concurrent cùng actor/key/payload → đúng 1 transfer tạo; N−1 request replay original (`Idempotency-Replayed: true`)
- [x] Biến thể khác payload cùng key (concurrent) → thua nhận `409 IDEMPOTENCY_KEY_REUSED`, không mutation

#### Task 5.4: CON-03 — Opposite-direction no deadlock (AC-03)
- [x] A→B và B→A concurrent → cả 2 complete (sorted lock); bounded timeout (`Future.get(timeout)`); không deadlock kéo dài

#### Task 5.5: FI-01 — Rollback giữa debit và credit (AF-01)
- [x] `TransferFailureInjector.afterDebitBeforeCredit()` — **prod profile: no-op bean; test profile: throw** — qua Spring Profile + bean injection, không `if (testMode)` (Arch §12)
- [x] Inject throw sau debit trước credit → assert rollback **toàn bộ**: source/dest balance unchanged, không transfer row COMPLETED, không audit fact committed, không risk flag
- [x] Verify injector không lọt prod profile (test assertions trên bean wiring)

### Day 7 (07/11) — Evidence + Review
- [x] Ghi balance trước/sau + row counts của CON-01..03 vào §4 (chuyển thành `evidence/BE3-concurrency.md` khi finalize W3)
- [x] Review chéo phần BE-Support (dispatch pairing + expiry + history filter) — self-review inline (1 harness phiên này, như W1)
- [x] `make check` pass

---

## 2. BE-Support — Dispatch Pairing + Expiry + History

### Day 1–2 (01–02/11) — OTP Dispatch Adapter

#### Task 2.1: Dispatch qua adapter của transfer
- [x] Wiring dispatch qua adapter (identity đã có `LocalMailboxOtpSender` + `OtpMailboxConfiguration` — dùng qua port/adapter, **không inject trực tiếp** vào service transfer)
- [x] Local mailbox = in-memory, chỉ cho local/demo — không gọi network thật trong test

#### Task 2.2: Pairing compensation (AF-03, AF-04)
- [x] Verify invalidate challenge là **atomic** với `UPDATE transfers` trong compensation TX (cùng TX hoặc cùng lock scope)
- [x] Verify `WHERE status='AWAITING_OTP'`: confirm-wins-before-compensation → COMPLETED giữ nguyên, compensation no-op an toàn (FI-04 phần pairing; race test đầy đủ là W3)
- [x] OTP gửi trễ sau compensation → challenge đã invalidated → OTP "đúng" cũng `409` (AT-03)

### Day 3–4 (03–04/11) — Expiration Policy

#### Task 2.3: `TransferExpirationPolicy.resolveEffectiveStatus()` ([Architecture §8](../plans/3.%20Architecture.md))
- [x] Lazy evaluation — **không** scheduled job trong MVP: `AWAITING_OTP` + `now > expires_at` → đọc ra `EXPIRED`
- [x] Đặt ở `domain/` hoặc `application/` theo kết quả refactor M03

#### Task 2.4: Áp effective status vào read path (giải quyết M12)
- [x] `GET /transfers` list + `GET /transfers/{id}` detail: hàng vật lý `AWAITING_OTP` quá hạn → response `EXPIRED` (AT-02)
- [x] Confirm path enforce `now > expires_at` dưới lock (pairing với BE-Core Task 3.1 bước 3)
- Ghi chú (chưa làm, ngoài scope MVP): nếu sau này thêm job persist chỉ `UPDATE ... WHERE status='AWAITING_OTP' AND expires_at < now()` — không touch terminal

#### Task 2.5: Tests expiry
- [x] Hàng vật lý `AWAITING_OTP` quá hạn, chưa ai đọc → GET list/detail trả `EXPIRED` (AT-02)
- [x] Confirm transfer quá hạn → `409 TRANSFER_EXPIRED` (AT-01)
- [x] Late OTP (AT-03): challenge expired/invalidated → OTP đúng vẫn `409`, không chuyển tiền

### Day 5–7 (05–07/11) — History Hoàn Chỉnh

#### Task 2.6: Filter `status` + `from`/`to` ([API docs §5](../plans/2.%20API%20docs.md))
- [x] `status`: `AWAITING_OTP | COMPLETED | EXPIRED | FAILED`
- [x] `from` inclusive / `to` exclusive, RFC3339 UTC (AT-04 — FE tự convert timezone, server chỉ nhận UTC)
- [x] `from >= to` khi cả hai có → `400 INVALID_DATE_RANGE`
- [x] Hiện trạng 01/11: `findHistory` đã nhận `status/from/to` (TransferQueryService) — verify validation + filter semantics đúng spec, đối chiếu index `idx_transfers_source_cursor` / `idx_transfers_dest_cursor`

#### Task 2.7: QV-01..06 full pass
- [x] QV-01..05 (đã pass ở W1) — re-run với filter + effective status
- [x] QV-06 masking: `counterpartyAccountMasked` chỉ 4 số cuối visible (resolve đã có — thêm phần list/detail nếu thiếu)
- [x] Malformed cursor → `400 VALIDATION_ERROR` (đã có — re-run)
- [x] Filter kết hợp cursor: predicate `(created_at, id) DESC` giữ stable khi filter

#### Task 2.8: Wrong-OTP persistence (pairing AC-06)
- [x] Test: sai OTP attempt 1–4 → attempts **committed** dù HTTP trả 400; sai lần 5 cross-check challenge invalidated + transfer FAILED cùng TX
- [x] Pattern tương tự AC-06 (PIN lockout concurrent) — W2 chỉ cần wrong-OTP persistence; lockout concurrent đầy đủ để W3 phối hợp BE-A

#### Task 2.9: Evidence
- [x] Ghi kết quả test vào §4 file này; chạy `make check`

---

## 3. Deliverables W2 — Checklist nghiệm thu

| # | Deliverable | Owner | Trạng thái |
|---|---|---|---|
| D1 | Large transfer + confirm + compensation + expiry pass end-to-end (SM-01..05, AT-01..03) | BE-Core + BE-Support | ✅ §4.4 + `TransferLargeTransferPostgresTest` 7/7 |
| D2 | CON-01..03 + FI-01 pass với PostgreSQL thật | BE-Core | ✅ §4.2, §4.3 — 4/4 + 3/3 |
| D3 | History/query full pass QV-01..06 + filter status/date | BE-Support | ✅ §4.5 — `TransferQueryServiceTest` 11/11 |
| D4 | Refactor M01/M03 + boundary OTP M09–M11 hoàn tất, suite W1 vẫn xanh (183+ tests) | BE-Core | ✅ 183 → **215 tests / 0 fail** |
| D5 | `Work/Week2.md` evidence hoàn chỉnh (file này) | Cả hai | ✅ §4 |

## 4. Evidence — Kết quả thực tế

> **Quy tắc:** Không ghi "PASS" mà không có fresh test output (Timelines §9). Paste output thật, kèm timestamp.

### 4.1 Baseline đầu tuần + tổng suite cuối tuần

**Baseline 01/11/2026 (trước khi sửa code W2):**

```
$ make check
OpenAPI validation passed: .../contracts/openapi.yaml (31 operations)
Tests: 183 / 0 fail   (JDK 21, Testcontainers postgres:16-alpine)
```

**Cuối tuần 01/11/2026 16:09–16:10 (+07:00) — chạy sạch `mvn clean` + `make check`:**

```
$ make check
cd be && python3 scripts/validate-openapi.py
OpenAPI validation passed: /Users/dllv/Documents/GitHub/cloude/contracts/openapi.yaml (31 operations)
cd be && JAVA_HOME="..." mvn -q test
git diff --check
MAKE_CHECK_EXIT:0
```

Aggregate từ `be/target/surefire-reports/TEST-*.xml` (fresh sau `mvn clean`):

```
CLASSES 62   TESTS 215   FAILURES 0   ERRORS 0   SKIPPED 0
```

Suite mới/đổi trong W2:

| Suite | Tests | Kết quả |
|---|---|---|
| `TransferLargeTransferPostgresTest` (Postgres thật) | 7 | ✅ 0 fail |
| `TransferConcurrencyPostgresTest` (Postgres thật) | 4 | ✅ 0 fail |
| `TransferFailureInjectionPostgresTest` (Postgres thật) | 3 | ✅ 0 fail |
| `CreateTransferServiceTest` | 16 | ✅ 0 fail |
| `ConfirmTransferOtpServiceTest` | 17 | ✅ 0 fail |
| `TransferQueryServiceTest` | 11 | ✅ 0 fail |
| `TransferPolicyTest` (chuyển sang `domain/`) | 7 | ✅ 0 fail |
| `TransferControllerMvcTest` | 7 | ✅ 0 fail |

183 → 215 (+32 net). Suite mới: `CreateTransferServiceTest` 16, `ConfirmTransferOtpServiceTest` 17, `TransferLargeTransferPostgresTest` 7, `TransferConcurrencyPostgresTest` 4, `TransferFailureInjectionPostgresTest` 3; `TransferPolicyTest` 7 chuyển sang `domain/`; `TransferQueryServiceTest` mở rộng 6 → 11; `TransferServiceTest`/`TransferServiceLifecycleTest` của service gộp được thay thế.

### 4.2 CON-01..03 — balance trước/sau + row counts

Fresh run `TransferConcurrencyPostgresTest` 01/11/2026: **4/4 pass** (1.04s–2.34s), harness `CountDownLatch ready/start + ExecutorService + Future.get(20s)`, không sleep.

| Scenario | Balance source trước → sau | Balance dest trước → sau | HTTP/service outcomes | `transfers` rows | `idempotency_records` |
|---|---|---|---|---|---|
| CON-01 overdraft | 1.000.000 → **400.000** | dest A + B: 0 + 0 → **600.000** (một dest nhận 600.000) | 1× `COMPLETED` + 1× `409 INSUFFICIENT_FUNDS` | **1** | không assert (2 key khác nhau; claim của request thua rollback cùng TX) |
| CON-02 same key (6 request) | 10.000.000 → **9.750.000** | 0 → **250.000** | 1× created + **5× replayed**, tất cả cùng `transferId` | **1** | **1** |
| CON-02 same key, khác payload | không đổi (không assert trực tiếp) | 0 → 100.000 **hoặc** 200.000 | 1× success + 1× `409 IDEMPOTENCY_KEY_REUSED` | **1** | **1** |
| CON-03 A↔B ngược chiều | 1.000.000 → **1.000.000** | 1.000.000 → **1.000.000** | 2× `COMPLETED`, hoàn tất trong timeout 20s | **2** | không assert |

Assert bổ sung CON-01: `SELECT count(*) FROM accounts WHERE balance < 0` = **0**.

Pairing compensation (Task 2.2): `TransferLargeTransferPostgresTest.compensationFailAfterCompletionTouchesNoRows` — gọi `transfers.fail(id, "OTP_DISPATCH_FAILED", ...)` sau khi transfer đã `COMPLETED` là **no-op** (`WHERE status='AWAITING_OTP'`): status/`completed_at`/`failure_code`/`expires_at` giữ nguyên. `CreateTransferServiceTest.dispatchFailureCompensatesTransferAndChallengeThenReturns503` verify `fail` + `invalidateOtp` cùng compensation TX.

### 4.3 FI-01 — rollback toàn bộ

Fresh run `TransferFailureInjectionPostgresTest` (profile `transfer-failure-injection`): **3/3 pass**.
`FoundationPostgresTest.defaultProfileWiresNoOpFailureInjector` pass — profile mặc định wire `NoOpTransferFailureInjector`.

| Kịch bản | Kết quả assert |
|---|---|
| Throw sau debit, trước credit (small create) | source **10.000.000**, dest **0** (không đổi); `transfers`=**0**; `audit_events`=**0**; `risk_flags`=**0**; `idempotency_records`=**0**; exception `IllegalStateException("FI-01...")` |
| Throw trong confirm sau debit | transfer vẫn **AWAITING_OTP**, `expires_at` NOT NULL; `otp_challenges.consumed_at`=**NULL**; source **10.000.000**, dest **0**; `audit_events WHERE event_type='TRANSFER_COMPLETED'`=**0**; `risk_flags`=**0** |
| Wiring profile | test profile → `ThrowingTransferFailureInjector`; default profile → `NoOpTransferFailureInjector` |

### 4.4 Expiry + AT-01..03

| Test (fresh) | Assert |
|---|---|
| `TransferLargeTransferPostgresTest.lazyExpiryMarksReadsExpiredAndLateOtpCannotMoveMoney` | Hàng vật lý `AWAITING_OTP` với `expires_at = now-1s`: `transfers.find` vẫn `AWAITING_OTP`; `queries.get` → **EXPIRED**; `queries.list(status=EXPIRED)` chứa transfer; DB vẫn `AWAITING_OTP` (không persist). Confirm OTP đúng → **409 TRANSFER_EXPIRED**; sau đó row **EXPIRED**, `expires_at` NULL, challenge `consumed_at` NULL, balances **10.000.000 / 0** |
| `ConfirmTransferOtpServiceTest.confirmAtExactExpiryInstantStillCompletes` | `now == expires_at` → vẫn COMPLETED (strict `now.isAfter`) |
| `ConfirmTransferOtpServiceTest.transferPastExpiresAtExpiresBeforeConsumingOtp` | `now > expires_at` → transfer EXPIRED + **409**, challenge không bị consume |
| `ConfirmTransferOtpServiceTest.expiredChallengeExpiresTransferWithoutMovingMoney` | Challenge hết hạn → transfer EXPIRED + **409**, không chuyển tiền (AT-03) |
| `ConfirmTransferOtpServiceTest.invalidatedOrAlreadyUsedChallengeCannotCompleteTransfer` | Challenge invalidated/already-used → **400 OTP_INVALID**, không chuyển tiền (AT-03) |
| `TransferLargeTransferPostgresTest.expiredTransferRejectsConfirmWithoutConsumingChallenge` | Row vật lý EXPIRED → **409 TRANSFER_EXPIRED**, challenge `consumed_at` NULL, balances unchanged |
| `TransferQueryServiceTest.listShowsLazilyExpiredStatusWithoutPersisting` / `detailShowsLazilyExpiredStatusForSourceOwner` | List/detail trả EXPIRED, không UPDATE DB (AT-02) |

### 4.5 History/QV full

Fresh `TransferQueryServiceTest` **11/11 pass** + `RecipientControllerMvcTest` 9/9:

| Nhóm | Test | Assert |
|---|---|---|
| Filter status | `passesStatusAndDateFiltersToRepositoryAndReturnsFilteredPage` | `status/from/to` truyền xuống repo đúng, page giữ filter |
| Filter status lazy | `listShowsLazilyExpiredStatusWithoutPersisting` | filter `EXPIRED` khớp hàng vật lý `AWAITING_OTP` quá hạn (CASE trong SQL) |
| Date range | `fromNotBeforeToRejectedWithInvalidDateRange` | `from >= to` → **400 INVALID_DATE_RANGE** |
| Status invalid | `invalidStatusFilterRejectedWithValidationError` | status lạ → **400 VALIDATION_ERROR** |
| Cursor | `historyMergesAllOwnedAccountsWithStableGlobalCursorAndCounterpartyProjection`, `nextCursorEncodedFromLastReturnedItemWhenRepositoryReturnsExtraRow` | cursor `(created_at, id) DESC`, `limit+1`, stable khi filter; QV-01..05 |
| Cursor malformed | `rejectsMalformedCursorBeforeQueryingTransfers` | **400 VALIDATION_ERROR**, không query |
| Visibility + masking | `sourceOwnerSeesAwaitingOtpInList`, `destinationOwnerSeesCompletedTransferDetail`, `destinationOwnerCannotSeeNonCompletedTransferDetail`, `RecipientControllerMvcTest` (9) | source/destination visibility đúng; QV-06 mask chỉ 4 số cuối |

## 5. MISMATCH Log — Code ↔ Docs

> Quan sát sơ bộ từ survey `transfer/**` ngày **01/11/2026**; trạng thái cập nhật cuối W2 **01/11/2026**.

| ID | Ngày | Hiện trạng | Kỳ vọng (docs) | Quyết định | Owner |
|---|---|---|---|---|---|
| M09 | 01/11 | `TransferService` inject trực tiếp `OtpChallengeRepository`, `OtpHashingService`, `OtpSender` (internals của `identity`) | Chỉ giao tiếp qua `IdentityModuleApi` (Arch §1, API docs §8) | ✅ Xử lý — service chỉ dùng port; internals nằm trong `IdentityModuleApiAdapter` (identity `OtpChallengeService.consume` yêu cầu phone nên adapter lock challenge theo `id` bằng SQL `FOR UPDATE`) | BE-Core |
| M10 | 01/11 | `TransferOtpRepository` (transfer) read/write bảng `otp_challenges` trực tiếp | ERD §4: transfer không trực tiếp đọc/write bảng này | ✅ Xoá `TransferOtpRepository`; toàn bộ logic chuyển vào adapter | BE-Core |
| M11 | 01/11 | Port `IdentityModuleApi` chưa có `issueOtp()`/`consumeOtp()` | API docs §8 định nghĩa 2 method này | ✅ Mở rộng port + thêm `dispatchOtp`/`invalidateOtp` (không sửa `identity/**`) | BE-Core |
| M12 | 01/11 | Query path chưa áp lazy expiry | `TransferExpirationPolicy` + AT-02 (Arch §8) | ✅ Domain policy + SQL CASE ở list/detail; không persist | BE-Support |
| M13 | 01/11 | `TransferQueryService` inject `AccountJdbcRepository` + `IdentityJdbcRepository` trực tiếp | Arch §1: không cross-module repository injection | ✅ Chấp nhận có kiểm soát (read-only, không chạm tiền) — ghi DECISIONS.md 01/11 | BE-Support |
| M14 | 01/11 | Chưa có `TransferFailureInjector` | Arch §10 FI-01 | ✅ Interface + NoOp (`!transfer-failure-injection`) + Throwing (test profile) | BE-Core |
| M15 | 01/11 | API docs §8 ghi `UUID issueOtp(...)`; thực tế cần thêm phone/code/expiresAt để dispatch sau COMMIT và `invalidateOtp` cho compensation | Port tối thiểu theo docs | ✅ Chấp nhận `OtpIssue` (challengeId, customerId, phone, code, expiresAt) + `dispatchOtp`/`invalidateOtp`; đề xuất cập nhật API docs §8 | BE-Core |
| M16 | 01/11 | Confirm cần verify actor sở hữu source account trước khi trả dữ liệu transfer | Concealment 404 (API docs §4) | ✅ Thêm `ownerCustomerId(accountId)` vào `AccountModuleApi` của transfer (không sửa module account) | BE-Core |

*(M01–M08 của W1 đã đóng — xem [Week1.md §5](Week1.md).)*

## 6. Dependencies W2 & Fallback

| Dependency | Owner | Trạng thái | Fallback nếu trễ |
|---|---|---|---|
| `IdentityModuleApi.issueOtp()/consumeOtp()` | BE-A | ✅ Đã có trong port transfer + adapter tự triển khai (M09/M11); đề xuất BE-A chuẩn hoá contract tương tự ở identity | — |
| `DisposablePostgresTestSupport` | BE-B | ⚠️ Vẫn chưa có — đã dùng fallback | Mỗi test class tự `@Testcontainers` postgres:16-alpine; **TODO đổi khi BE-B xong** (R2) |
| Block race (CON-05) phối hợp BE-A | BE-A | — W3 | Đặt lịch phối hợp trước cuối W2 (R4) |

*Ghi chú phiên: như W1, phiên này 1 harness làm trực tiếp cả BE-Core + BE-Support trong cùng worktree — review inline thay cho review PR (Timelines §1).*

## 7. Quyết định & Ghi chú tuần

*(Mọi quyết định ảnh hưởng thiết kế đã copy sang DECISIONS.md.)*

- 01/11: W2 bắt đầu thực tế trễ 5 ngày so với lịch (27/10). Re-baseline: 01/11–07/11, W3 dự kiến 08/11–14/11. Không cắt scope concurrency (Timelines §9). Toàn bộ task W2 hoàn tất trong phiên 01/11.
- 01/11: Refactor M01/M03 — `CreateTransferService` + `ConfirmTransferOtpService`, domain package; suite W1 (183) xanh lại trước khi viết code W2.
- 01/11: Boundary OTP M09–M11 — adapter tự INSERT challenge (không gọi `OtpChallengeService.issue` vì method này gửi OTP trong TX), dispatch sau COMMIT; `consumeOtp` lock challenge theo `id` bằng raw SQL (method consume của identity yêu cầu phone); `identity/**` không đổi. Đề xuất BE-A: expose lifecycle issue/dispatch/consume/invalidate ở identity API chuẩn.
- 01/11: Idempotency complete trong money TX; dispatch sau COMMIT; compensation TX `WHERE status='AWAITING_OTP'` + invalidate challenge → `503`. Replay sau `503` thấy `FAILED(OTP_DISPATCH_FAILED)` — hành vi chấp nhận trong W2 (không retry gửi OTP).
- 01/11: Confirm trả outcome rồi mới ném HTTP error sau COMMIT — attempt OTP/FAILED/EXPIRED persist dù response 400/409 (AC-06, Task 3.2).
- 01/11: Expiry strict `now.isAfter(expiresAt)` — Java và SQL (`expires_at < now`) khớp nhau; `now == expires_at` vẫn confirm được.
- 01/11: M13 chấp nhận cross-module read repository ở query path (ghi DECISIONS.md); M15/M16 phát sinh từ port mới.
- 01/11: `make check` cuối tuần: **215 tests / 0 fail**, OpenAPI 31 operations, `git diff --check` sạch.

## 8. Checklist tan ca (mỗi ngày)

- [x] Cập nhật evidence §4 (fresh output nếu chạy test)
- [x] MISMATCH mới → §5
- [x] `make check` pass
- [x] Commit phần việc hoàn thành (chỉ trong `transfer/**` + `docs transactions/**`)
