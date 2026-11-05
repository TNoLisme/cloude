# BE3 — Rollback / Timeout / Restart Evidence (FI-01..05, AD-01..03, AF-05)

> **Commit:** `fd3364cd3d8aa53f8820518bf2f57c011a747693` (nhánh `transactions`)
> **Thời điểm chạy:** 2026-11-01T09:36:40Z – 2026-11-01T09:38:17Z (16:36–16:38 giờ máy, Asia/Ho_Chi_Minh)
> **Môi trường:** JDK 21 (Temurin 21), Spring Boot 3.3.13, PostgreSQL 16 (`postgres:16-alpine`, Testcontainers per-class)
> **Lệnh:** `mvn -Dtest='TransferConcurrencyPostgresTest,TransferOtpRacePostgresTest,TransferReplayPostgresTest,TransferRiskIntegrationPostgresTest,TransferRiskFailureIsolationPostgresTest,TransferLargeTransferPostgresTest,TransferFailureInjectionPostgresTest' test`
> **Fresh output:** 29 tests / 0 failures / 0 errors (xem [BE3-otp-race §1](BE3-otp-race.md#1-kết-quả-batch-fresh-output)). File này dùng các class `TransferFailureInjectionPostgresTest` (3/3), `TransferReplayPostgresTest` (3/3), `TransferLargeTransferPostgresTest` (7/7).

## 1. FI-02 — Timeout-after-commit (AF-02, AD-01)

**Kịch bản:** create small 2.000.000 từ source 10.000.000 thành công (201), client bỏ response lần 1 rồi retry **cùng key + cùng payload** thêm **3 lần**.

| Chỉ số | Kết quả |
|---|---|
| Lần 1 | `replayed=false`, `COMPLETED`, `completed_at` NOT NULL |
| Retry 1..3 | `replayed=true`, cùng `transferId`, cùng `COMPLETED` |
| `transfers` | **1** |
| `idempotency_records` | **1** (`response_status=201`, `resource_id=transferId`) |
| Balances | source 10.000.000 → **8.000.000**; destination 0 → **2.000.000** |
| `audit_events TRANSFER_COMPLETED` | **1** |

**AD-01:** idempotency record được `complete(...)` trong cùng money TX (`CreateTransferService.java:116`), nên không có cửa sổ “đã commit tiền nhưng chưa ghi outcome”. Test assert response status/resource ngay sau lần 1 đã là 201 + transferId.

**Kết luận:** retry sau timeout chỉ replay body gốc, không debit lần hai; 3 retry vẫn chỉ 1 transfer, 1 audit.

## 2. FI-01 — Rollback toàn bộ (AF-01)

**Fresh output:** `TransferFailureInjectionPostgresTest` — **3 tests / 0 failures / 0 errors**, time 4.258s.

| Kịch bản | Kết quả |
|---|---|
| Throw sau debit (trước credit) | source **10.000.000**, destination **0**; `transfers`=**0**; `audit_events`=**0**; `risk_flags`=**0**; `idempotency_records`=**0** |
| Throw trong confirm (sau debit, trước commit) | transfer vẫn **`AWAITING_OTP`**, `expires_at` NOT NULL; challenge `consumed_at`=**NULL**; source **10.000.000**, destination **0**; `audit_events WHERE event_type='TRANSFER_COMPLETED'`=**0**; `risk_flags`=**0** |

**Kết luận:** mọi mutation tiền/audit/risk/idempotency nằm trong một transaction; rollback trả DB về trạng thái trước lệnh, kể cả attempt OTP không bị đốt.

## 3. FI-03 — Dispatch fail → compensation (AF-03)

**Fresh test:** `TransferLargeTransferPostgresTest.dispatchFailureCompensatesTransferAndChallengeAndReplaySeesFailedState`.

**Setup:** source 10.000.000, destination 0, amount 6.000.000; `OtpSender.send` luôn throw.

| Chỉ số | Kết quả |
|---|---|
| Create response | `503 SERVICE_UNAVAILABLE` (exception `ApiException`) |
| Transfer | **1 row** `FAILED`, `failure_code=OTP_DISPATCH_FAILED` |
| Challenge | `invalidated_at` NOT NULL |
| Balances | **10.000.000 / 0** (chưa từng debit) |
| `transfers WHERE status='AWAITING_OTP'` | **0** — không lệnh chờ mồ côi |
| Retry cùng key | `replayed=true`, `status=FAILED`; vẫn **0** `AWAITING_OTP` |
| `OtpSender.send` | gọi **đúng 1 lần** — replay không gửi OTP mới |

**Kết luận:** dispatch sau COMMIT; fail được compensation `WHERE status='AWAITING_OTP'` + invalidate challenge trong TX riêng; replay thấy terminal FAILED thay vì tạo lệnh mới.

## 4. FI-04 — Confirm-vs-compensation race (AF-04)

Chi tiết đầy đủ ở [BE3-otp-race §4](BE3-otp-race.md#4-fi-04--confirm-wins-before-compensation-af-04). Tóm tắt:

| Thứ tự | Transfer | Balances | Compensation | Confirm sau |
|---|---|---|---|---|
| Confirm thắng | `COMPLETED`, giữ nguyên | **4.000.000 / 6.000.000** | no-op an toàn | — |
| Compensation thắng | `FAILED(OTP_DISPATCH_FAILED)` | **10.000.000 / 0** | thắng, challenge invalidated | `409 STATE_CONFLICT` |

**Kết luận:** guard status bảo vệ terminal trong cả hai thứ tự; không có ghi đè `completed_at`/`failure_code`.

## 5. FI-05 — App restart giữa commit và retry (AD-02)

**Mô phỏng thật:** create 2.000.000 commit xong; dựng **Spring context thứ hai** (`SpringApplicationBuilder(BankingSimulatorApplication.class).web(SERVLET)`) trỏ vào **cùng DataSource Testcontainers**, không reuse bean/context cũ; gọi create cùng key qua bean `CreateTransferService` của context mới.

Cấu hình context mới (command-line args để thắng `application.yml`):

```text
--server.port=0
--spring.datasource.url=<Testcontainers JDBC URL>
--spring.datasource.username=<Testcontainers user>
--spring.datasource.password=<Testcontainers password>
--app.security.jwt-secret=<32+ bytes test secret>
```

| Chỉ số | Kết quả |
|---|---|
| Replay qua context mới | `replayed=true`, cùng `transferId`, `COMPLETED` |
| Balances | **8.000.000 / 2.000.000** (không đổi thêm) |
| `transfers` | **1** |
| `idempotency_records` | **1** |
| `audit_events TRANSFER_COMPLETED` | **1** |

**Kết luận:** idempotency nằm ở PostgreSQL, không phải bộ nhớ app; restart không làm mất outcome và không debit lại. Không dùng fallback “clear map thủ công”.

## 6. AF-05 — Accepted limitation (không phải bug)

> **Crash sau khi transfer `AWAITING_OTP` đã COMMIT nhưng trước khi dispatch OTP** → OTP không bao giờ được gửi; transfer tự hết hạn theo TTL và đọc ra `EXPIRED` (lazy expiry), không có job/Outbox trong MVP.

- Phạm vi: chỉ ảnh hưởng large transfer đang chờ OTP; **không** mất tiền (chưa debit), **không** tạo debit/credit.
- Đội nghiệm thu: đây là **accepted limitation** của MVP (Adv §7), không phải lỗi cần fix trong W3. Outbox pattern là hạng mục kiến trúc sau MVP.
- Evidence liên quan: AT-02 lazy expiry (`TransferLargeTransferPostgresTest.lazyExpiryMarksReadsExpiredAndLateOtpCannotMoveMoney`) — row vật lý `AWAITING_OTP` quá hạn đọc ra `EXPIRED`, balances 10.000.000 / 0.

## 7. AD-03 — Key retention hết hạn

**Setup:** tạo transfer 2.000.000 với key; set `created_at = now - 2 days`, `expires_at = now - 1 day` (giữ CHECK `expires_at > created_at`); retry cùng key + payload.

| Chỉ số | Kết quả |
|---|---|
| Lần 2 | `replayed=false`, `transferId` **mới** |
| Balances | source **6.000.000**, destination **4.000.000** (2 lần debit) |
| `transfers` | **2** |
| `idempotency_records` | **1** (row cũ vẫn còn nhưng `find` lọc `expires_at > CURRENT_TIMESTAMP`; row mới thay thế theo unique key) |

**Kết luận:** sau retention window, cùng key được xem là intent mới; không replay nhầm outcome cũ.

## 8. AC-06 pairing — trạng thái

| Phần | Trạng thái | Bằng chứng |
|---|---|---|
| Wrong-OTP attempt 1..4 phải commit dù HTTP 400 | **PASS** | `TransferLargeTransferPostgresTest.wrongOtpAttemptsAreCommittedThenFifthFailsTransfer` (fresh batch 7/7); attempt 5 → `FAILED(OTP_ATTEMPTS_EXCEEDED)` + `409` |
| PIN lockout concurrent (identity module) | **Ngoài scope transfer — chờ BE-A** | Transfer chỉ nhận `403 PIN_LOCKED`; không sửa `identity/**` (AGENTS.md). Cần test concurrent 5 lần sai PIN trong 15 phút phía identity — ghi ở handover [BE3-handover §3](BE3-handover.md#3-handover-be-a-identityaccount) |

## 9. Kết luận nhóm rollback/timeout/restart

- FI-01..05 pass trên PostgreSQL thật; rollback không để lại transfer/audit/risk/idempotency mồ côi.
- Timeout retry cùng key replay đúng body gốc, debit đúng một lần.
- Dispatch fail tạo terminal `FAILED(OTP_DISPATCH_FAILED)` + invalidate challenge + `503`; replay không gửi OTP mới.
- Restart thật (context thứ hai) vẫn replay từ DB.
- AF-05 được ghi nhận là accepted limitation, AT-02 lazy expiry là hành vi thay thế trong MVP.
