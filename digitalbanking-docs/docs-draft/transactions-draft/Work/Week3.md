# Week 3 — Races, Risk, Acceptance, Handover

> **Kế hoạch gốc:** 03/11 – 09/11/2026 · **Re-baseline (từ W2):** W2 hoàn thành 01/11 → W3 chạy **08/11 – 14/11/2026** (7 ngày, giữ nguyên khối lượng).
> **Trạng thái:** ✅ **Hoàn thành phần BE-Core + BE-Support trong phiên 01/11/2026** (sớm hơn re-baseline). D5 load test ghi **blocked-note có điều kiện** tại [evidence/BE3-handover.md §9](../evidence/BE3-handover.md#9-load-test-r5--blocked-note) — không ghi PASS.
> **Kế hoạch nguồn:** [5. Timelines §5](../plans/5.%20Timelines.md) — tài liệu này khai triển chi tiết + là nơi ghi evidence thực tế.
> **Tham chiếu kỹ thuật:** [2. API docs](../plans/2.%20API%20docs.md) §3–§5, §9–§10 · [3. Architecture](../plans/3.%20Architecture.md) §8–§10 · [7. Advanced Business](../plans/7.%20Advanced%20Business.md) (AC-04..08, AF-02..07, AT-01..04, AD-01..03) · [6. Business](../plans/6.%20Business.md) §3, §5–§7
> **Baseline đầu tuần:** 215 tests / 0 fail (Java 21, `make check` pass 01/11/2026 — [Week2 §4.1](Week2.md))
> **Điểm nhấn W3:** toàn bộ là **race/failure/risk ở biên** — mọi test dùng PostgreSQL thật (Testcontainers postgres:16-alpine), harness `CountDownLatch ready/start + ExecutorService + Future.get(20s)`, **không sleep-sync, không H2** (Timelines §9).

---

## 0. Mục tiêu tuần (Definition of Done)

| # | Mục tiêu | Nguồn |
|---|---|---|
| G1 | CON-04 (duplicate confirm) + CON-05 (block-vs-confirm, phối hợp BE-A) pass với PostgreSQL thật | Arch §10, Adv §2 (AC-04, AC-05) |
| G2 | FI-02..05 pass: timeout-after-commit (AF-02), dispatch compensation (AF-03), confirm-wins race (AF-04), app restart (AD-02) | Arch §10, Adv §3–§5 |
| G3 | Risk AFTER_COMMIT integration hoàn chỉnh: LARGE_TRANSFER + HIGH_FREQUENCY gắn flag đúng; risk failure **không** ảnh hưởng response/balance đã commit (AF-06) | Arch §9, Business §3 UC-06, BR-R1..R4 |
| G4 | Full acceptance matrix ([Timelines §6](../plans/5.%20Timelines.md)) pass + evidence **3 files** fresh output | Timelines §5, §6 |
| G5 | Load test (k6, 20 RPS × 10 phút) có report hoặc blocked-note có điều kiện (R5) + FE error matrix + handover BE-A/BE-B/FE | Timelines §5 |

**Không thuộc scope W3 (chủ ý, không phải thiếu sót):** cancel/resend OTP endpoint (Business §8), scheduled job persist EXPIRED (AT-02 — lazy là đủ), Outbox pattern cho AF-05 (accepted limitation), multi-instance deploy test (idempotency đã do DB unique đảm bảo — Adv AC-02), sửa `identity/**`/`account/**`/`risk/**` (chỉ proposal — AGENTS.md).

**Nguyên tắc suốt tuần:** nếu trượt lịch thì **trượt lịch, không cắt chuẩn test** (Timelines §7 "Không được nhân nhượng", §9). W3 là tuần nghiệm thu — mọi "PASS" phải có fresh output paste vào §4/evidence.

---

## 1. BE-Core — Races + Failure Injection + Risk

### Day 1–2 (08–09/11) — OTP Races

#### Task 1.1: CON-04 — Duplicate confirm (AC-04)

**Setup:** transfer 6.000.000 đang `AWAITING_OTP` (create large path, OTP lấy từ local mailbox adapter); 2 confirm concurrent với **cùng OTP đúng**, harness `CountDownLatch ready/start` — 2 thread cùng release một lúc, `Future.get(20s)`.

**Luồng phải xảy ra (theo lock order transfer → challenge → accounts ASC):**

| | Confirm thắng lock | Confirm thua lock |
|---|---|---|
| Transfer row | `COMPLETED` + `completed_at` set | Đọc thấy `COMPLETED` khi lock được → **replay 200** + `Idempotency-Replayed: true` **trước khi đụng OTP** (bước 2 của confirm 8 bước) |
| OTP challenge | `consumeOtp` đúng 1 lần (`consumed_at` NOT NULL) | Không consume |
| Balance | Debit/credit **đúng 1 lần** | Không đổi thêm |

- [x] Assert: source/dest balance đổi đúng 1× amount; `otp_challenges.consumed_at` NOT NULL và chỉ 1 row challenge liên quan; 2 outcome = 1× `201 COMPLETED` + 1× `200 replay` (cùng body); không còn `AWAITING_OTP` → [BE3-otp-race §2](../evidence/BE3-otp-race.md#2-con-04--duplicate-confirm-ac-04), `con04ConcurrentDuplicateConfirmDebitsOnceAndReplaysTheOther`
- [x] Assert tổng quát (invariant): `SELECT count(*) FROM accounts WHERE balance < 0` = 0; tổng source+dest giảm đúng amount của source → cùng test, fresh batch 7/7
- [x] Biến thể: confirm thứ 2 dùng **OTP sai** (FE bug gửi lại OTP cũ/sai) khi transfer đã COMPLETED → vẫn replay 200, **không** đếm attempt (replay check đứng trước consume) → `con04ReplayAfterCompletionWithWrongOtpDoesNotCountAnAttempt`

#### Task 1.2: CON-05 — Block-vs-confirm race (AC-05, phối hợp BE-A)

**Setup:** transfer 6.000.000 đang `AWAITING_OTP`; concurrent: (a) Operator block source account (module account), (b) khách confirm OTP đúng.

**Hai hướng race — đều phải an toàn:**

| Hướng | Ai thắng lock account trước | Kết quả phải xảy ra |
|---|---|---|
| Block trước confirm | Block commit `status=BLOCKED` | Confirm re-validate dưới lock thấy không ACTIVE → transfer `FAILED(ACCOUNT_NOT_ELIGIBLE)` **terminal**, `409`, balance **không đổi**, challenge invalidate/consume theo flow confirm |
| Confirm trước block | Confirm commit `COMPLETED` + debit/credit | Block sau đó chỉ chặn lệnh **tương lai** — transfer COMPLETED **giữ nguyên**, không "đảo ngược" giao dịch đã xong |

- [x] Hướng 1: assert `FAILED(ACCOUNT_NOT_ELIGIBLE)` + `failure_code` đúng + balances unchanged + `WHERE status='AWAITING_OTP'` style guard không cần ở đây (guard là điều kiện terminal, không phải của confirm) → `con05BlockBeforeConfirmFailsTransferAsTerminalWithoutMovingMoney`
- [x] Hướng 2: assert COMPLETED giữ nguyên + balance đã đổi đúng 1 lần + block không rollback → `con05ConfirmBeforeBlockKeepsCompletedTransferAndBlockOnlyGuardsFutureTransfers`; race thật `con05ConcurrentBlockAndConfirmAlwaysLeaveAConsistentOutcome`
- [x] **Phối hợp BE-A (R4 — đã đặt lịch từ cuối W2):** dùng đúng operation block của account module trong test (qua HTTP hoặc module API của account, không tự viết SQL update `accounts.status` ngoài operation chuẩn nếu có thể). Nếu BE-A chưa kịp: fallback dùng UPDATE trực tiếp trong test (Testcontainers sở hữu DB) **+ ghi chú blocked-dependency trong evidence**, integration bổ sung với BE-A sau (giữ nguyên chuẩn assert) → dùng `AccountStatusService.change(operator, accountId, "BLOCKED", reason)` thật; không fallback SQL
- [x] Ghi `evidence/BE3-otp-race.md` (xem §3 cấu trúc file) → đã tạo, fresh output + SHA + balance/row counts

#### Task 1.3: Evidence OTP race

- [x] Fresh output CON-04/05 (balance trước/sau, row counts, HTTP outcomes) → `evidence/BE3-otp-race.md`; kèm AT-01..03 reference từ [Week2 §4.4](Week2.md) (cùng nhóm evidence theo Adv §8) → [BE3-otp-race](../evidence/BE3-otp-race.md) (commit `fd3364c`, 29 tests / 0 fail)

### Day 3–4 (10–11/11) — Failure Injection FI-02..05

#### Task 3.1: FI-02 — Timeout-after-commit (AF-02, AD-01)

**Kịch bản:** money TX đã commit (201 COMPLETED trả về hoặc bị "mất" trước khi client nhận) → client retry **cùng key + cùng payload** (nhập lại PIN).

- [x] Mô phỏng timeout: create thành công, **bỏ qua** response của lần 1 (không dùng kết quả ở client-side), gọi lại `create()` cùng key/hash — đây chính là replay path W1/W2 nhưng assert lại dưới góc độ nghiệp vụ AF-02 → `fi02RetryAfterCommitReplaysOriginalOutcomeWithoutASecondDebit`
- [x] Assert: `200` + `Idempotency-Replayed: true` + **body gốc** (`transferId`, `completedAt` không đổi); debit/credit vẫn đúng **1 lần** (balance không đổi thêm sau retry); `transfers` = 1 row → [BE3-rollback-timeout §1](../evidence/BE3-rollback-timeout.md#1-fi-02--timeout-after-commit-af-02-ad-01), balances 8.000.000 / 2.000.000
- [x] Retry N lần (≥3) cùng key → vẫn 1 transfer, N−1 replay → 3 retry, `1 transfer / 1 idempotency row / 1 audit`
- [x] Note AD-01: idempotency outcome persist **cùng money TX** → không có cửa sổ "đã commit nhưng chưa ghi idempotency" — assert record status là COMPLETED ngay sau TX (đã có từ W1, re-verify) → response status 201 + `resource_id` ngay sau lần 1; `CreateTransferService.java:116` ghi trong money TX

#### Task 3.2: FI-03 — Dispatch fail → compensation (AF-03) — formalize trên Postgres

Hiện trạng: pass ở W2 (`CreateTransferServiceTest.dispatchFailureCompensatesTransferAndChallengeThenReturns503` — unit; `TransferLargeTransferPostgresTest.compensationFailAfterCompletionTouchesNoRows` — pairing). W3 formalize:

- [x] Fresh Postgres run: dispatch fail → `503` + transfer `FAILED(OTP_DISPATCH_FAILED)` + challenge invalidated + balances unchanged + **không còn `AWAITING_OTP`** (không lệnh chờ mồ côi) → `dispatchFailureCompensatesTransferAndChallengeAndReplaySeesFailedState`; balances 10.000.000 / 0; 0 row `AWAITING_OTP`
- [x] Replay same-key sau 503 → thấy `FAILED(OTP_DISPATCH_FAILED)` (documented behavior W2 — DECISIONS.md 01/11), không gửi OTP mới → `replayed=true`, `verify(sender, times(1))`

#### Task 3.3: FI-04 — Confirm-wins-before-compensation race (AF-04)

**Kịch bản đua thật:** dispatch OTP chậm (inject delay/throw ở sender adapter, test profile) — trong lúc hệ thống chuẩn bị compensation thì khách nhập đúng OTP và confirm chạy trước.

- [x] Harness: thread A confirm (OTP đúng), thread B compensation (`fail(OTP_DISPATCH_FAILED)`); sync bằng `CountDownLatch` — **không sleep-sync** (Timelines §9). Có thể chạy cả 2 thứ tự (confirm-first, compensation-first) nếu scheduling không deterministic — mỗi thứ tự assert outcome tương ứng → `ControlledDispatch` (`started`/`release` latch) + 2 test thứ tự xác định
- [x] Confirm thắng: transfer **COMPLETED giữ nguyên**, debit/credit đúng 1 lần; compensation là **no-op an toàn** (`WHERE status='AWAITING_OTP'` không match row) — status/`completed_at`/`failure_code` không bị ghi đè → `fi04ConfirmWinsBeforeCompensationAndCompensationIsANoop`; balances 4.000.000 / 6.000.000
- [x] Compensation thắng: transfer `FAILED(OTP_DISPATCH_FAILED)`, challenge invalidated, confirm sau đó → OTP đã chết → `409 STATE_CONFLICT` (không chuyển tiền) → `fi04CompensationWinsThenConfirmRejectsDeadOtpWithoutMovingMoney`; balances 10.000.000 / 0
- [x] Cả hai hướng: terminal không bao giờ bị ghi đè (ma trận race Adv §6 dòng "Confirm-vs-compensation") → [BE3-otp-race §4](../evidence/BE3-otp-race.md#4-fi-04--confirm-wins-before-compensation-af-04)

#### Task 3.4: FI-05 — App restart giữa commit và retry (AD-02)

**Câu hỏi nghiệp vụ:** idempotency record nằm ở DB (không phải bộ nhớ app) → restart không mất, retry sau restart vẫn replay đúng.

- [x] Mô phỏng restart **trong cùng JVM**: chạy create commit xong, dựng **Spring context mới** (`SpringApplicationBuilder` cùng DataSource Testcontainers, không reuse bean/context cũ) → gọi create cùng key qua service của context mới → assert replay 200 + body gốc + 1 debit/credit → `fi05RetryThroughAFreshApplicationContextStillReplaysTheCommittedResult` (context 2 với command-line args; 1 transfer/1 debit)
- [x] Nếu dựng context thứ 2 quá nặng (thời gian build > giá trị chứng minh): fallback tối thiểu — assert replay path **chỉ đọc DB** (không cache/Map in-memory nào phục vụ replay; verify bằng review code + unit test tắt mọi state app) + ghi chú điều kiện mô phỏng còn thiếu trong evidence. **Không** fake bằng clear map thủ công → không cần fallback; context thật dựng được ~1.5s
- [x] Ghi `evidence/BE3-rollback-timeout.md` — kèm **accepted limitation AF-05** (crash sau AWAITING_OTP commit trước dispatch → tự EXPIRED ≤120s, best-effort, không Outbox trong MVP — Adv §7) — viết rõ "không phải bug" cho đội nghiệm thu → [BE3-rollback-timeout §6](../evidence/BE3-rollback-timeout.md#6-af-05--accepted-limitation-không-phải-bug)

### Day 5–6 (12–13/11) — Risk AFTER_COMMIT + Terminal Guard

#### Task 5.1: Risk integration — LARGE_TRANSFER + HIGH_FREQUENCY + failure isolation (AF-06, UC-06)

**Hiện trạng 01/11 (đối chiếu code):** `TransferCommittedEvent` đã publish từ `CreateTransferService` + `ConfirmTransferOtpService` (skeleton từ W1); `risk/application/TransferCommittedRiskListener` đã có `@TransactionalEventListener(AFTER_COMMIT)` + catch RuntimeException + `RiskEvaluationService.evaluate(...)`. W3 là **verify integration end-to-end với Postgres thật** — không viết lại money path.

- [x] **Test 1 — LARGE_TRANSFER:** transfer COMPLETED amount 6.000.000 → `risk_flags` có flag rule `LARGE_TRANSFER` (versioned), `transfer_id`/`source_account_id` đúng; transfer ≤5M → không flag → `af06LargeCompletedTransferGetsLargeTransferFlagAndFiveMillionDoesNot`
- [x] **Test 2 — HIGH_FREQUENCY:** > 5 transfer COMPLETED trong 10 phút từ cùng source (dùng `Clock` injectable điều khiển window — không sleep) → flag `HIGH_FREQUENCY` đúng rule version; fan-out AC-08 là tín hiệu nghiệp vụ — flag **không chặn**, chỉ cảnh báo → `af06SixthCompletedTransferForTheSameSourceGetsHighFrequencyFlag`; balances 9.400.000 / 600.000
- [x] **Test 3 — Risk failure isolation (AF-06):** inject throw trong risk evaluation (test profile/bean) → response transfer **không đổi** (201/200 bình thường), transfer COMPLETED + debit/credit **đã commit giữ nguyên**, không rollback, không exception leak ra HTTP; chỉ thiếu flag + log warn (verify qua log assertion hoặc không-flag) → `af06RiskFailureAfterCommitLeavesResponseAndMoneyUntouched`; balances 9.900.000 / 100.000, 0 flag
- [x] **Test 4 — Rollback không trigger risk:** transfer fail trước commit (dùng FI-01 injector) → **không** có `risk_flags` row (AFTER_COMMIT chỉ chạy khi commit thành công) → FI-01 test (0 flags) + `af06TransferThatNeverCommitsDoesNotTriggerRiskEvaluation` (`verify(risk, never())`)
- [x] **Test 5 — Risk chậm hơn giao dịch:** risk fail **không** làm response chậm thêm khác thường / không đổi outcome (định tính qua cấu trúc listener — risk chạy AFTER_COMMIT trong luồng response nhưng độc lập TX; ghi nhận hiệu ứng latency Adv §7 để đo ở load test) → xác nhận định tính bằng listener AFTER_COMMIT + isolation test; **đo latency định lượng bị blocked** cùng load test (R5)
- [x] **Ràng buộc boundary:** mọi thay đổi cần ở rule risk → **proposal cho owner `risk/**`**, không tự sửa (AGENTS.md). Nếu rule/threshold thiếu cấu hình: ghi MISMATCH §5 + fallback test theo behavior hiện tại → không sửa `risk/**`; ghi nhận threshold/version hiện tại trong evidence
- [x] Quyết định thiết kế phát sinh (nếu có) → copy sang DECISIONS.md → mục 01/11/W3 trong DECISIONS.md

#### Task 5.2: Terminal guard — tổng hợp SM-01..03 + guard trên mọi path

- [x] Re-run SM-01..03 (đã pass W2) cùng batch với risk tests — 1 report nghiệm thu duy nhất → batch 29 tests (gồm `TransferLargeTransferPostgresTest` 7/7) tại commit `fd3364c`
- [x] Thêm assert chéo: **mọi** path ghi transfer đều qua guard điều kiện status (`complete`/`expire`/`fail` đều `WHERE status IN (...)` hợp lệ) — đối chiếu CON-04/05 + FI-04 đã chứng minh bằng hành vi; chỉ cần bảng tổng hợp "path → điều kiện guard → test chứng minh" trong evidence → [BE3-concurrency §3](../evidence/BE3-concurrency.md#3-terminal-guard--tổng-hợp-sm-0103-task-52)

#### Task 5.3: Finalize `evidence/BE3-concurrency.md`

- [x] Chuyển CON-01..03 (+ biến thể AC-07) từ [Week2 §4.2](Week2.md) sang `evidence/BE3-concurrency.md` — giữ fresh output gốc, **không copy chữ "PASS" không kèm output** → [BE3-concurrency §1](../evidence/BE3-concurrency.md#1-con-0103--postgresql-thật-không-sleep-sync)
- [x] Re-run 1 lần trên HEAD cuối tuần để output đồng nhất về commit → fresh batch trên `fd3364c`; output + timestamp ghi đầu mỗi evidence file

### Day 7 (14/11) — Evidence + Review

- [x] Đối chiếu 3 evidence files với ma trận [Adv §8](../plans/7.%20Advanced%20Business.md) — mọi nhóm AC/AF/AT/AD có nơi ghi → bảng mapping tại §4.5 + đầu mỗi evidence file
- [x] Review chéo phần BE-Support (acceptance run + FE matrix) — inline như W1/W2 (1 harness phiên này) → review inline trong phiên; xác nhận từng bên ghi tại [BE3-handover §10](../evidence/BE3-handover.md#10-xác-nhận-bàn-giao)
- [x] `make check` pass trên HEAD cuối tuần; commit → kết quả cuối tại §4.1; commit code `fd3364c`, docs commit sau khi hoàn tất evidence

---

## 2. BE-Support — Acceptance Run + Load Test + FE Matrix + Handover

### Day 1–3 (08–10/11) — Acceptance Run + Evidence

#### Task 2.1: Chạy toàn bộ suite theo lệnh chuẩn

```bash
mvn -q -Dtest="*Transfer*,*Recipient*" test
mvn -q -Dtest="*Transfer*PostgresTest,TransferUiApiRegressionPostgresTest" test
git diff --check
make check
```

- [x] Aggregate surefire reports (CLASSES/TESTS/FAILURES) paste vào §4.1 — pattern như [Week2 §4.1](Week2.md) → **CLASSES=66, TESTS=230, FAILURES=0, ERRORS=0, SKIPPED=0**
- [x] Chạy sạch `mvn clean` trước batch nghiệm thu cuối (tránh stale reports — quy tắc W2) → `mvn clean` → 2 lệnh targeted exit 0 → `make check` exit 0

#### Task 2.2: Acceptance matrix — Timelines §6 từng dòng

Với **mỗi** nhóm checklist dưới, ghi kết quả + test ID chứng minh vào evidence tương ứng (không chỉ tick ở file này):

- [x] **Financial Invariants:** AF-01 (FI-01), AC-01 (CON-01), AF-02 (FI-02), AC-03 (CON-03) → [BE3-rollback-timeout](../evidence/BE3-rollback-timeout.md), [BE3-concurrency §1](../evidence/BE3-concurrency.md#1-con-0103--postgresql-thật-không-sleep-sync)
- [x] **State Machine:** SM-01..05 + boundary 5M/5M+1 (SM-04/05) + wrong-OTP 1..4/5 + expired + terminal-final → `TransferLargeTransferPostgresTest` 7/7 + [BE3-concurrency §3](../evidence/BE3-concurrency.md#3-terminal-guard--tổng-hợp-sm-0103-task-52)
- [x] **Idempotency:** same-key same/diff payload, concurrent, duplicate confirm, restart (CON-02, FI-02, FI-05) → `TransferConcurrencyPostgresTest` 4/4 + `TransferReplayPostgresTest` 3/3 + CON-04
- [x] **Query & Visibility:** QV-01..06 + filter inclusive/exclusive → W2 [Week2 §4.5](Week2.md) (11/11 `TransferQueryServiceTest`), re-run trong `make check` cuối
- [x] **Security:** PIN invalid/locked trước OTP, IDOR concealment, wrong-attempt committed trước HTTP error (AC-06 phần wrong-OTP đã pass W2 — phần PIN lockout concurrent pairing BE-A ở Task 2.5) → wrong-OTP PASS (`wrongOtpAttemptsAreCommittedThenFifthFailsTransfer`); PIN lockout concurrent **chờ BE-A** ([BE3-handover §7](../evidence/BE3-handover.md#7-handover-be-a-identityaccount))
- [x] **Infrastructure:** không H2 (verify mọi concurrency/lock test là `*PostgresTest`); injector không lọt prod profile; `DisposablePostgresTestSupport` — đối chiếu trạng thái BE-B (R2), nếu đã có thì đổi trả, chưa có thì ghi fallback đang dùng → tất cả race tests là `*PostgresTest`; injector chỉ ở profile `transfer-failure-injection`; per-class Testcontainers được giữ ([BE3-handover §8](../evidence/BE3-handover.md#8-handover-be-b-audittest-support))

#### Task 2.3: Load test k6 (R5)

- [ ] Kịch bản: **20 RPS × 10 phút**, transfer mix (≈80% small ≤5M / 20% large + confirm OTP), k6 chạy local chống app local (compose), seed data tự tạo (customer + accounts + balance đủ) → **BLOCKED**: k6 chưa cài, backend image stale, demo credentials trống; điều kiện mở lại tại [BE3-handover §9](../evidence/BE3-handover.md#9-load-test-r5--blocked-note)
- [ ] Metric bắt buộc: p50/p95/p99 latency `POST /transfers` + `confirm-otp`, error rate theo HTTP code, throughput thực tế → **BLOCKED** cùng lý do
- [ ] **Invariant dưới load:** zero negative balance (`SELECT count(*) FROM accounts WHERE balance < 0` = 0 sau run), zero duplicate debit (so total debit vs COMPLETED transfers × amount trên same-key tập), rate limit resolve vẫn hoạt động → chưa có dữ liệu load; invariant đã chứng minh ở CON-01..05
- [x] Ghi report (screenshot/script output + số liệu) vào `evidence/` (file load-test hoặc mục riêng trong `BE3-concurrency.md`) → blocked-note + điều kiện nghiệm thu bổ sung tại [BE3-handover §9](../evidence/BE3-handover.md#9-load-test-r5--blocked-note)
- [ ] Nếu FE/k6 phối hợp chưa sẵn sàng: tự chạy k6 local + **blocked-note** ghi rõ điều kiện thiếu để nghiệm thu bổ sung (Timelines §5 R5) — không bỏ hẳn → blocked-note đã ghi; phần “tự chạy k6 local” chưa thực hiện vì k6 không có trên máy, không ghi PASS

#### Task 2.4: Đối soát post-load (reconciliation sanity)

- [ ] Chọn 5–10 Idempotency-Key từ tập load test, replay thủ công → tất cả trả replay/kết quả gốc, không tạo transfer mới (chứng minh AC-02/AF-02 ở quy mô > unit test) → **BLOCKED** theo load test; đã có replay 6 request concurrent (CON-02) + 3 retry (FI-02) trên DB thật

### Day 4–5 (11–12/11) — FE Error Matrix + Docs

#### Task 2.5: FE handoff matrix

- [x] **State machine flow:** AWAITING_OTP → (confirm | expire | attempts-exceeded | dispatch-fail) → terminal; không path nào hồi sinh; diagram + mô tả FE mỗi trạng thái hiển thị gì → [BE3-handover §1](../evidence/BE3-handover.md#1-state-machine-fe-cần-hiển-thị)
- [x] **Boundary 5M:** E1 (5.000.000 → 201 COMPLETED ngay) / E2 (5.000.001 → 200 AWAITING_OTP) — FE không tự quyết định path theo amount, chỉ theo response status ([Business §6](../plans/6.%20Business.md)) → [BE3-handover §2](../evidence/BE3-handover.md#2-boundary-5000000-e1e2)
- [x] **Idempotency protocol:** key sinh 1 lần khi user confirm (UUID, 16–128 printable ASCII); retry cùng key + payload + nhập lại PIN; **không** sinh key mới sau timeout; **không** suy ra outcome từ timeout (UC-04); branch theo `code` không theo `detail` → [BE3-handover §3](../evidence/BE3-handover.md#3-idempotency-protocol-bắt-buộc)
- [x] **Timeout = unknown + reconcile guide (UC-04):** retry same-key hoặc `GET /transfers/{id}`; bảng outcome replay theo trạng thái hiện tại (AWAITING_OTP → TTL còn lại, không OTP mới; terminal → kết quả terminal) → [BE3-handover §4](../evidence/BE3-handover.md#4-timeout--unknown--reconcile-uc-04)
- [x] **OTP attempt/expiry:** 400 OTP_INVALID còn N lần (server track, FE không tự đếm); lần 5 → 409 STATE_CONFLICT + transfer FAILED; 409 TRANSFER_EXPIRED → đóng modal, tạo lệnh mới; countdown chỉ là UI hint — server là thẩm quyền (AT-01, AT-04 clock skew: mọi mốc theo UTC, FE convert trước khi filter `from`/`to`) → [BE3-handover §5](../evidence/BE3-handover.md#5-otp-attempts--expiry--cancel-resend)
- [x] **Không cancel/resend OTP** — không có endpoint, có chủ ý (UC-03); FE không hiện nút → ghi trong [BE3-handover §5](../evidence/BE3-handover.md#5-otp-attempts--expiry--cancel-resend)
- [x] **Error matrix đầy đủ:** mỗi endpoint × HTTP × `code` → hành vi FE khuyến nghị (nguồn [API docs §3–§7](../plans/2.%20API%20docs.md)); gồm `503 SERVICE_UNAVAILABLE` (retriable — AF-07) và `409 IDEMPOTENCY_KEY_REUSED` (FE bug sinh key — phải sinh key mới) → [BE3-handover §6](../evidence/BE3-handover.md#6-error-matrix-đầy-đủ-endpoint--http--code)
- [x] AC-06 pairing BE-A: phần PIN lockout concurrent — ghi trạng thái/điều kiện phối hợp trong matrix → [BE3-handover §7](../evidence/BE3-handover.md#7-handover-be-a-identityaccount) (A3, chờ BE-A)

#### Task 2.6: Update docs theo kết quả thực tế

- [x] Rà `plans/` đối chiếu behavior cuối: đóng MISMATCH còn mở (M13 nợ kỹ thuật có kiểm soát — giữ nguyên ghi nhận; M15 đã có đề xuất BE-A), cập nhật [API docs §8](../plans/2.%20API%20docs.md) nếu contract thực tế (`OtpIssue`/`dispatchOtp`/`invalidateOtp`) được BE-A chấp nhận chuẩn hoá → rà xong; M15 vẫn proposal (BE-A chưa chấp nhận) nên **không sửa API docs §8**; phát sinh **M17** (API docs ghi 429 cho create transfer nhưng implementation chưa rate-limit endpoint này) tại §5
- [x] Finalize 3 evidence files (xem §3 cấu trúc) → [BE3-otp-race](../evidence/BE3-otp-race.md), [BE3-concurrency](../evidence/BE3-concurrency.md), [BE3-rollback-timeout](../evidence/BE3-rollback-timeout.md); bổ sung [BE3-handover](../evidence/BE3-handover.md) cho D6

### Day 6–7 (13–14/11) — Handover

- [x] **BE-A (identity/account):** (a) chuẩn hoá OTP lifecycle contract theo `IdentityModuleApi` của transfer — issue không gửi trong TX, dispatch sau COMMIT, consume lock theo challenge id, invalidate (M15/đề xuất W2); (b) wrong-attempt commit trước HTTP error đã được transfer chứng minh (Task 2.8 W2) — đối chiếu phía identity PIN; (c) lockout concurrent AC-06 — kết quả phối hợp; (d) block race CON-05 — kết quả phối hợp → [BE3-handover §7](../evidence/BE3-handover.md#7-handover-be-a-identityaccount): A1/M15 proposal, A2 transfer PASS, A3 chờ BE-A, A4 CON-05 PASS
- [x] **BE-B (audit/test-support):** (a) injector design (`TransferFailureInjector` + Spring Profile — pattern tái sử dụng); (b) PostgreSQL outputs (EXPLAIN lock order, CON-01..05); (c) invariant summary (8 guarantees Business §7 + guard bảng Task 5.2); (d) đổi trả `DisposablePostgresTestSupport` — quyết định giữ pattern per-class hay migrate (R2) → [BE3-handover §8](../evidence/BE3-handover.md#8-handover-be-b-audittest-support): B1 injector, B2 CON outputs, B3 guard table, B4 hold per-class
- [x] **FE:** matrix Task 2.5 + walkthrough state machine + timeout/unknown + OTP UX → [BE3-handover §1–§6](../evidence/BE3-handover.md#1-state-machine-fe-cần-hiển-thị); trạng thái xác nhận ⏳ chờ FE
- [ ] Cập nhật `PROGRESS.md` (trạng thái W3 + milestone module) + `DECISIONS.md` (mọi quyết định W3) — quy tắc tan ca AGENTS.md → thực hiện ở Task #7
- [ ] `make check` pass cuối cùng; commit phần việc hoàn thành (chỉ `transfer/**` + `docs transactions/**`) → thực hiện ở Task #7

---

## 3. Deliverables W3 — Checklist nghiệm thu

| # | Deliverable | Owner | Nguồn evidence |
|---|---|---|---|
| D1 | CON-04/05 pass PostgreSQL thật (CON-05 có ghi chú phối hợp BE-A hoặc blocked-note fallback) | BE-Core | ✅ `evidence/BE3-otp-race.md` (7/7, dùng `AccountStatusService` thật) |
| D2 | FI-02..05 pass + AF-05 accepted-limitation note | BE-Core | ✅ `evidence/BE3-rollback-timeout.md` (FI-02 3/3, FI-03/FI-04/FI-05 pass; AF-05 note §6) |
| D3 | Risk AFTER_COMMIT integration pass (LARGE_TRANSFER, HIGH_FREQUENCY, failure isolation AF-06, rollback không trigger) | BE-Core | ✅ `evidence/BE3-concurrency.md` §2 (3/3 + 2/2) |
| D4 | Full acceptance matrix (Timelines §6) pass, fresh output | BE-Support | ✅ 3 evidence files + §4.1 (fresh clean run) |
| D5 | Load test report (hoặc blocked-note có điều kiện — R5) | BE-Support | ⚠️ **BLOCKED-NOTE** — `evidence/BE3-handover.md` §9; không ghi PASS |
| D6 | FE error matrix + handover notes BE-A/BE-B/FE + PROGRESS/DECISIONS cập nhật | BE-Support | ✅ `evidence/BE3-handover.md` + §4.5; PROGRESS/DECISIONS ở Task #7 |

### Cấu trúc thư mục evidence (finalize tuần này)

```text
docs transactions/
└── evidence/                          ← tạo mới trong W3 (quyết định ghi DECISIONS.md)
    ├── BE3-concurrency.md             ← CON-01..03 + AC-07 variant (chuyển từ Week2 §4.2) + risk Task 5.1 (hoặc BE3-risk.md tách riêng)
    ├── BE3-otp-race.md                ← CON-04, CON-05, FI-04, AT-01..03 (ref Week2 §4.4)
    └── BE3-rollback-timeout.md        ← FI-01..05, AF-05 limitation, AD-01..03, AC-06 pairing
```

Mỗi file theo khuôn: scenario → setup → fresh output (paste thật + timestamp + commit SHA) → balance trước/sau + row counts → kết luận. **Không ghi "PASS" không kèm output** (Timelines §9).

---

## 4. Evidence — Kết quả thực tế

> **Quy tắc:** Không ghi "PASS" mà không có fresh test output (Timelines §9). Paste output thật, kèm timestamp. Mục này điền dần trong tuần.

### 4.1 Baseline đầu tuần + tổng suite cuối tuần

**Baseline 08/11/2026 (kế thừa W2):** 215 tests / 0 fail, `make check` pass 01/11/2026.

**Fresh evidence batch 01/11/2026 (commit `fd3364c`, JDK 21, PostgreSQL 16):**

```text
[INFO] Running com.bank.simulator.transfer.TransferConcurrencyPostgresTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.45 s
[INFO] Running com.bank.simulator.transfer.TransferFailureInjectionPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.258 s
[INFO] Running com.bank.simulator.transfer.TransferReplayPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.625 s
[INFO] Running com.bank.simulator.transfer.TransferRiskFailureIsolationPostgresTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.223 s
[INFO] Running com.bank.simulator.transfer.TransferLargeTransferPostgresTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.03 s
[INFO] Running com.bank.simulator.transfer.TransferRiskIntegrationPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.397 s
[INFO] Running com.bank.simulator.transfer.TransferOtpRacePostgresTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.93 s
[INFO] Results:
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Suite mới/đổi W3:** `TransferOtpRacePostgresTest` (7), `TransferReplayPostgresTest` (3), `TransferRiskIntegrationPostgresTest` (3), `TransferRiskFailureIsolationPostgresTest` (2); `TransferLargeTransferPostgresTest` bổ sung assert không còn `AWAITING_OTP` sau dispatch-fail/replay.

- [x] Cuối tuần: `mvn clean` + `make check` fresh — paste aggregate (CLASSES/TESTS/FAILURES) + danh sách suite mới/đổi của W3:

```text
$ mvn clean
$ mvn -q -Dtest='*Transfer*,*Recipient*' test          # exit 0
$ mvn -q -Dtest='*Transfer*PostgresTest,TransferUiApiRegressionPostgresTest' test   # exit 0
$ make check                                            # exit 0 (openapi + mvn test + git diff --check)

Aggregate surefire (target/surefire-reports/TEST-*.xml):
CLASSES=66  TESTS=230  FAILURES=0  ERRORS=0  SKIPPED=0
Timestamp: 2026-11-01T09:49:54Z (16:49 giờ máy) — HEAD fd3364c
```

### 4.2 CON-04/05 + FI-04 (otp-race)

| Case | Trước | Sau | Outcome | Row counts |
|---|---|---|---|---|
| CON-04 duplicate confirm (6.000.000) | 10.000.000 / 0 | **4.000.000 / 6.000.000** | 1× non-replay + 1× replay; OTP consume 1 lần | transfers 1, challenges 1, negative 0 |
| CON-04 replay với OTP sai | 4.000.000 / 6.000.000 | không đổi | replay, attempts không tăng | transfers 1 |
| CON-05 block-first | 10.000.000 / 0 | **10.000.000 / 0** | `FAILED(ACCOUNT_NOT_ELIGIBLE)`, `409` | transfers 1, risk_flags 0 |
| CON-05 confirm-first | 10.000.000 / 0 | **4.000.000 / 6.000.000** | `COMPLETED`, account BLOCKED sau đó | transfers 1 |
| FI-04 confirm thắng | 10.000.000 / 0 | **4.000.000 / 6.000.000** | COMPLETED giữ nguyên, compensation no-op, create `503` | transfers 1 |
| FI-04 compensation thắng | 10.000.000 / 0 | **10.000.000 / 0** | `FAILED(OTP_DISPATCH_FAILED)`, confirm `409 STATE_CONFLICT` | transfers 1, challenge invalidated |

Fresh output + per-test time: [BE3-otp-race](../evidence/BE3-otp-race.md). Kết luận: không duplicate debit, terminal không bị ghi đè, OTP chỉ consume một lần.

### 4.3 FI-02..05 + AD-01..03 (rollback-timeout)

| Case | Kết quả | Bằng chứng |
|---|---|---|
| FI-01 rollback after debit | balances 10.000.000 / 0; transfers/audit/risk/idempotency = 0 | `TransferFailureInjectionPostgresTest` 3/3 |
| FI-01 rollback during confirm | `AWAITING_OTP`, challenge chưa consume, balances 10.000.000 / 0 | cùng file |
| FI-02 retry 3 lần same key | 1 transfer, `completedAt` không đổi, balances 8.000.000 / 2.000.000, 1 audit | `TransferReplayPostgresTest` 3/3 |
| FI-03 dispatch fail | `503`, `FAILED(OTP_DISPATCH_FAILED)`, 0 `AWAITING_OTP`, balances 10.000.000 / 0, OTP gửi 1 lần | `TransferLargeTransferPostgresTest` |
| FI-04 | xem §4.2 | `TransferOtpRacePostgresTest` |
| FI-05 restart (context thứ 2) | replay đúng body, 1 transfer, 1 idempotency, 1 audit | `TransferReplayPostgresTest` |
| AD-01 | idempotency 201/resource ghi trong money TX | FI-02 test |
| AD-03 key hết hạn | 2 transfer, balances 6.000.000 / 4.000.000, 1 idempotency row | `TransferReplayPostgresTest` |
| AF-05 accepted limitation | crash trước dispatch → lazy EXPIRED, không mất tiền | [BE3-rollback-timeout §6](../evidence/BE3-rollback-timeout.md#6-af-05--accepted-limitation-không-phải-bug) |

Chi tiết + fresh output: [BE3-rollback-timeout](../evidence/BE3-rollback-timeout.md).

### 4.4 Risk integration + load test

**Risk integration (fresh):**

| Case | Kết quả | Test |
|---|---|---|
| LARGE_TRANSFER 6.000.000 | 1 flag `LARGE_TRANSFER` v1, đúng source; 5.000.000 → 0 flag | `af06LargeCompletedTransferGetsLargeTransferFlagAndFiveMillionDoesNot` |
| HIGH_FREQUENCY | 5 lệnh đầu 0 flag; lệnh 6 → 1 flag `HIGH_FREQUENCY` v1; balances 9.400.000 / 600.000 | `af06SixthCompletedTransferForTheSameSourceGetsHighFrequencyFlag` |
| Risk failure isolation AF-06 | response COMPLETED, balances 9.900.000 / 100.000, 1 audit, 0 flag, evaluate vẫn được gọi | `af06RiskFailureAfterCommitLeavesResponseAndMoneyUntouched` |
| Rollback không trigger | `409 INSUFFICIENT_FUNDS`; risk không gọi; 0 flag/transfer | `af06TransferThatNeverCommitsDoesNotTriggerRiskEvaluation` |
| Terminal guard | complete/expire/fail không đổi terminal; UPDATE ngược bị CHECK từ chối | `terminalStatesAreNeverOverwrittenByCompleteExpireOrFail` |

**Load test (R5):** ⚠️ **BLOCKED-NOTE** — k6 chưa cài; backend image stale (build 21/10, trước V8); `.env` không có `DEMO_*`; chưa có kịch bản FE. Không có p50/p95/p99 trong phiên này. Điều kiện mở lại + invariant cần đo: [BE3-handover §9](../evidence/BE3-handover.md#9-load-test-r5--blocked-note).

### 4.5 FE error matrix + handover

- **FE:** state machine, boundary 5M, idempotency, timeout/reconcile, OTP UX, error matrix đầy đủ (gồm 503 + 409 IDEMPOTENCY_KEY_REUSED) → [BE3-handover §1–§6](../evidence/BE3-handover.md#1-state-machine-fe-cần-hiển-thị). Xác nhận FE: ⏳ chờ.
- **BE-A:** OTP lifecycle (M15), wrong-attempt PIN, AC-06 lockout concurrent, CON-05 → [BE3-handover §7](../evidence/BE3-handover.md#7-handover-be-a-identityaccount). Xác nhận: ⏳ chờ.
- **BE-B:** injector, CON outputs, guard table, `DisposablePostgresTestSupport` (hold per-class) → [BE3-handover §8](../evidence/BE3-handover.md#8-handover-be-b-audittest-support). Xác nhận: ⏳ chờ.
- **Mapping Adv §8:** AC-01..08 (CON-01..05, FI-02, AF-06), AF-01..07 (FI-01..05, AF-05 note, AF-06 isolation), AT-01..04 (W2 §4.4 + fresh large test), AD-01..03 (FI-02/FI-05/AD-03), QV-01..06 (W2 §4.5) — không nhóm nào thiếu nơi ghi.

---

## 5. MISMATCH Log — Code ↔ Docs

> M17+ đăng ký trong tuần; M01–M16 đã xử lý/ghi nhận ở [Week1 §5](Week1.md) + [Week2 §5](Week2.md).

| ID | Ngày | Hiện trạng | Kỳ vọng (docs) | Quyết định | Owner |
|---|---|---|---|---|---|
| M17 | 01/11/2026 | `POST /transfers` **không** có rate limit; interceptor chỉ áp `recipient-resolve` và `operator-lookup` (`RateLimitHandlerInterceptor.java:59–60`) | [API docs §3](../plans/2.%20API%20docs.md) liệt kê `429 RATE_LIMITED` cho create transfer | Giữ behavior hiện tại trong W3 (không sửa `shared/ratelimit` ngoài scope transfer); FE xử lý phòng thủ; đề xuất owner shared/identity bổ sung policy nếu cần | BE-A / shared owner |

Dự kiến khu vực có thể phát sinh MISMATCH: risk rule threshold/version cấu hình, FI-05 cách mô phỏng restart, CON-05 cách gọi block operation của account, cấu trúc `evidence/` mới. **Kết quả rà cuối:** risk threshold/version khớp behavior test; FI-05 dựng context thật; CON-05 dùng operation account thật; `evidence/` giữ 3 file + 1 handover; M15 vẫn là proposal (chưa được BE-A chấp nhận) — không sửa API docs §8.

---

## 6. Dependencies W3 & Fallback

| Dependency | Owner | Trạng thái | Fallback nếu trễ |
|---|---|---|---|
| Block operation (account module) cho CON-05 | BE-A | ✅ Dùng `AccountStatusService.change` thật trong test | Không cần fallback SQL; giữ tích hợp operation chuẩn |
| FE/k6 phối hợp load test | FE | ⚠️ BLOCKED — k6 chưa cài, backend image stale, không có `DEMO_*` | k6 local/Docker + rebuild backend + seed data + blocked-note (R5) tại [BE3-handover §9](../evidence/BE3-handover.md#9-load-test-r5--blocked-note) |
| `DisposablePostgresTestSupport` | BE-B | ⚠️ Chưa có từ W2 (R2) | **Hold** pattern per-class `@Testcontainers` cho MVP; migrate khi CI cần share container ([BE3-handover §8](../evidence/BE3-handover.md#8-handover-be-b-audittest-support)) |
| Risk rules cấu hình (threshold, version) | owner `risk/**` | ✅ Behavior hiện tại: LARGE_TRANSFER `>5M` v1, HIGH_FREQUENCY `>5`/10 phút v1 | Không sửa `risk/**`; test theo behavior hiện tại + proposal nếu owner đổi threshold |

*Rghi chú phiên: như W1/W2, phiên này 1 harness làm trực tiếp cả BE-Core + BE-Support trong cùng worktree — review inline thay cho review PR (Timelines §1).*

---

## 7. Quyết định & Ghi chú tuần

*(Mọi quyết định ảnh hưởng thiết kế copy sang DECISIONS.md — quy tắc tan ca AGENTS.md.)*

- 01/11: Tạo thư mục `docs transactions/evidence/` với 3 file theo nhóm race/rollback/concurrency + `BE3-handover.md` cho FE/BE-A/BE-B; mỗi file có commit SHA + timestamp + fresh output, không ghi PASS suông.
- 01/11: FI-05 mô phỏng restart bằng **Spring context thứ hai** trỏ cùng Testcontainers DataSource; dùng command-line args (`--spring.datasource.*`, `--app.security.jwt-secret`) để thắng `application.yml`. Không dùng fallback “clear map”.
- 01/11: CON-05 dùng `AccountStatusService.change` (operation thật của account module) thay vì UPDATE SQL — đáp ứng R4, không cần blocked-dependency.
- 01/11: FI-04 dùng `OtpSender` mock với `Answer` có latch `started`/`release` để cố định hai thứ tự race, không sleep-sync.
- 01/11: Risk failure isolation tách file riêng với `@MockBean RiskEvaluationService` để `doThrow` không ảnh hưởng fixture khác; rollback-not-trigger kiểm bằng `verify(never())`.
- 01/11: Giữ pattern per-class `@Testcontainers` (không có `DisposablePostgresTestSupport`); quyết định **hold** cho MVP, migrate khi CI cần share container.
- 01/11: Load test R5 ghi **blocked-note** (k6 chưa cài, backend image stale, thiếu demo credentials/kịch bản FE); không hạ chuẩn và không ghi PASS. M17 (rate limit create) ghi nhận là mismatch docs↔code, không tự sửa module shared.

---

## 8. Checklist tan ca (mỗi ngày)

- [x] Cập nhật evidence §4 / `evidence/*` (fresh output nếu chạy test) → 4 file evidence + §4.1 batch 29/29
- [x] MISMATCH mới → §5 → M17 (rate limit create transfer) + ghi nhận M15 chưa đóng
- [x] `make check` pass → exit 0; aggregate 66 classes / 230 tests / 0 fail (2026-11-01T09:49:54Z)
- [x] Commit phần việc hoàn thành (chỉ trong `transfer/**` + `docs transactions/**`) → code `fd3364c`; docs commit sau bước này (Week3 + `evidence/`)
