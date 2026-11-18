# BE-TRF-04 — Idempotency, canonical hash, T1 và step-up

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-04`
> **Phiên bản:** v1.1
> **Ngày cập nhật:** 18/11/2026
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** Đã triển khai candidate (Chờ ghép tích hợp provider thật & kiểm chứng PostgreSQL end-to-end)
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, Identity (Đạt), Account/Customer (Đạt), Limit (Vũ), Audit (Thịnh), Common (Phương), DBA/Architect, API owner, QA và Security

## 1. Mục tiêu và phạm vi

Xây dựng lớp idempotency và intent bền vững cho `POST /api/v1/transfers`: chuẩn hóa payload và tính canonical hash bằng JCS/SHA-256 của `common`; tra cứu khóa toàn cục và phân loại replay/conflict/in-progress theo `07` §5; điều phối step-up `T0` (challenge gắn user/hash/key, gửi OTP ngoài transaction sau commit) và đánh giá ngưỡng dưới customer guard theo `DEC-TRF-008`; thực hiện `T1` tạo transfer `PENDING` + idempotency `RECEIVED` + reservation + tiêu thụ challenge + ghi audit `TRANSFER_CREATED` trong một transaction riêng theo mô hình insert-first với ràng buộc unique toàn cục làm trọng tài. Task nối seam `TransferOrchestrator` để boundary `BE-TRF-03` ánh xạ kết quả sang HTTP response theo đúng hợp đồng.

**Ngoài phạm vi:** boundary HTTP/validation/authorization (`BE-TRF-03`); giao dịch hạch toán `T2` money/ledger/limit/fraud/audit C2-C1/outbox/terminal (`BE-TRF-05`); recovery worker, purge job và reconciliation (`BE-TRF-06`); telemetry/notification wiring (`BE-TRF-07`). Task này không tự chạy `T2` từ HTTP retry, không giữ transaction qua bước chờ OTP/mạng, không viết lại JCS/error stack/session của `common`, và không nhận actor/customer từ request body.

**Hiện trạng triển khai (`18/11/2026`):**
- Các primitive `TransferRequestHash` (tự băm payload bất biến, `toString` redacted), `IdempotencyDecision` (phân loại `NEW`/`REPLAY`/`IN_PROGRESS`/`CONFLICT`, kiểm tra actor/customer/hash trước khi lộ trạng thái), `StepUpAmountPolicy` (ngưỡng `>= 10.000.000 VND`/lệnh hoặc `> 20.000.000 VND`/ngày), `IdempotencyRetention` (snapshot TTL `>= 24h`, expiry ban đầu/terminal, điều kiện purge) và `TransferReferenceGenerator` (`SecureRandom`, định dạng `^TXN[0-9]{8}[A-Z0-9]{8,20}$`) đã hoàn thành.
- `TransferPreflightCandidate` (13 unit tests với fake): tra cứu khóa trước preflight, đọc lại khi bị từ chối nghiệp vụ, xác minh binding command `T1`, `ChallengeRequired` mang `requestHash`, `Settlement` receipt nhất quán kết quả; phân giải số tài khoản ủy quyền qua port `Account`.
- `TransferIntentCandidate` (14 unit tests với fake): transaction `REQUIRES_NEW` + `READ_COMMITTED`, lấy guard → kiểm tra lại khóa → đánh giá step-up → chèn `transfer`/`idempotency_record` (`step_up_challenge_id = NULL`) → tạo reservation → tiêu thụ challenge theo UUID → gắn FK `BIGINT` (`DEC-TRF-017`) → flush → ghi audit `TRANSFER_CREATED` cuối cùng; nếu thua unique key thì rollback toàn bộ `T1` rồi đọc lại trong transaction mới; `ChallengeRejectedException` và `AuditWriteException` làm rollback toàn bộ `T1`; cấu hình TTL snapshot fail-closed.
- `TransferCreateOrchestrator` hiện thực seam `TransferOrchestrator` cho boundary `BE-TRF-03`: ánh xạ kết quả, gọi `T0` `requestChallenge` sau gate, ánh xạ mã lỗi `409`/`410`/`500`/`503`; `StepUpChallengePort` ánh xạ `TRFC-ID-02/05` (5 tests). Lượt `./gradlew check` đạt **366 tests backend, 163 tests Transfer**, 0 lỗi. Xem [evidence BE-TRF-04/05](evidence/BE-TRF-04-05-candidate.md).

## 2. Nguồn và dependency

- [[digitalbanking-docs/docs-common/07. API contracts.md]] §5 (5.1–5.5), §6 (6.1–6.6), §8.3.1, §4.3: idempotency, canonical hash, step-up, mã lỗi và quy tắc xử lý; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].
- [[digitalbanking-docs/docs-common/03. SRS.md]] §2.13, `AC-010/011` (replay/conflict), `AC-033` (`428` không tạo bản ghi, xử lý một lần), `AC-018/034` (audit); [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.1/§4.4.3 (`transfer`/`idempotency_record`), §4.2.5 (unique active challenge).
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5.1–§5.2, §6, §7.3/§7.7, §11.4, Appendix A.6–A.7/B.2–B.3: preflight/T0, T1 insert-first, lock order và challenge; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §5.2, §8, §10.1, §11.1, §13.4.
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6–§5.7: PII allowlist và sự kiện không ghi audit; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §6, §11–§12; [Thiết kế Transfer](../design.md) §2–§5; [Backlog](README.md) §2; [DECISIONS.md](../../../../../DECISIONS.md) (`DEC-TRF-001/002/004/005/008/011/012/013/015/016/017`).
- [[digitalbanking-docs/docs-common/09. Test plan.md]] §7.3–§7.4.1, §10: oracle `T-IDEM-*`, `T-STEP-*`, `CT-API-007–010`.

| Dependency | Cần cho BE-TRF-04 | Trạng thái / cách xử lý |
|---|---|---|
| `BE-TRF-01` | Baseline contract v1 (`DEC-TRF-015`), oracle idempotency/step-up và quyết định replay/TTL | Baseline `05` §13.4 đã chốt cho phát triển độc lập; chờ tích hợp liên module. |
| `BE-TRF-02` | Schema/migration `transfer`, `idempotency_record` (TTL version, challenge FK, unique) và persistence primitives | Đã có migration/JPA candidate (`db/transfer-candidate`, chuyển thành `V7` khi tích hợp theo `DEC-COM-001`). |
| `BE-TRF-03` | Boundary HTTP, seam `TransferOrchestrator` và mapping outcome `TransferCreateOutcome` | Candidate đã có; kích hoạt end-to-end khi ghép đủ provider theo `DEC-TRF-016`. |
| `identity` — Đạt | `requestChallenge`/`findVerifiedBindingIfPresent`/`consumeVerified` (`TRFC-ID-02/05/04`), session thật | Đã triển khai trong `identity`; chờ ghép adapter với `transfer` và kiểm thử PostgreSQL race/rollback. |
| `account`/`customer` — Đạt | `resolveDestination` (`TRFC-AC-01`), `assertOwnership` (`TRFC-AC-02`) cho preflight | Dùng fake theo vector `05` §13.4; chờ provider `account` thật để nghiệm thu. |
| `limit` — Vũ | `lockStepUpGuard`/`evaluateStepUpUnderGuard`/`reserveForPendingTransfer` (`TRFC-LI-04/05`) | Hợp đồng đã chốt (`DEC-TRF-008/015`); kiểm thử với fake và chờ ghép provider `limit` thật. |
| `audit` — Thịnh | `recordInTransaction` C1 `TRANSFER_CREATED` (`MANDATORY`, fail-closed, `TRFC-AU-01`) | Component writer đã có (`BE-AUD-01`); chờ nối public port `AuditModuleApi` và kiểm chứng `T-AUD-07`. |
| `common` — Phương | `JcsCanonicalizer`, `HmacService` (SHA-256), `Clock`, request context, Problem/error catalog | Đã hoàn thành và được tái sử dụng trực tiếp. |
| `BE-TRF-05` | Seam `settleCreated` (`T2`) sau khi `T1` commit; terminal hóa idempotency/response | Candidate `BE-TRF-05` đã có; `BE-TRF-04` gọi qua seam. |

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / điều kiện nghiệm thu |
|---|---|---|
| `TRF-04.1` | Canonical hash và test vector | Chuẩn hóa đúng §4.1 theo `07` §5.2/`02` §6.2; JCS + SHA-256 `common`, lowercase hex 64 ký tự; vector cố định cho thứ tự trường, scale amount, null/rỗng, destination type, leading zero; hash không đổi khi retry cùng payload, đổi khi đổi biểu diễn; không ghi log raw key/body/PII. |
| `TRF-04.2` | Lookup, phân loại và replay idempotency | Tra cứu khóa toàn cục trên primary sau auth/schema/hash và **trước** khi phân giải/đọc tài khoản; đối chiếu actor phía server với `initiated_by_user_id` + customer + hash trước khi lộ trạng thái; terminal replay đúng status/body gốc (`201`/`422`) + `Idempotent-Replayed: true`; nonterminal trả `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`; conflict trả `409 IDEMPOTENCY_CONFLICT` + audit `C3` best-effort; preflight từ chối phải đọc lại khóa trên primary trước khi trả lỗi. |
| `TRF-04.3` | Step-up `T0` và guard/reservation | Đánh giá dưới customer guard theo `DEC-TRF-008`: tổng `COMPLETED` ngày hiện tại + mọi `PENDING` reservation còn hiệu lực + lệnh mới; ngưỡng mặc định `>= 10.000.000 VND`/lệnh hoặc `> 20.000.000 VND`/ngày; kiểm tra lại khóa dưới guard; nhánh `428` không tạo transfer/idempotency/ledger/reservation; challenge gắn user/hash/key, gửi OTP sau commit, chỉ trả `428` sau khi gateway ACK và cập nhật `SENT`. |
| `TRF-04.4` | `T1` intent bền vững | Một transaction riêng (`REQUIRES_NEW`, `READ_COMMITTED`): guard → kiểm tra lại khóa → chèn `transfer` `PENDING` (`step_up_challenge_id = NULL`) → chèn `idempotency_record` `ON CONFLICT DO NOTHING RETURNING` → tạo reservation → tiêu thụ challenge `VERIFIED` đúng binding một lần → cập nhật FK `step_up_challenge_id` (`DEC-TRF-017`) → flush → ghi audit `TRANSFER_CREATED` cuối cùng; thua unique hoặc lỗi audit làm rollback toàn bộ `T1`. |
| `TRF-04.5` | TTL và retention | `T1` đọc snapshot `TTL_T1` hợp lệ `>= 24h` + version, đặt `expires_at = created_at + TTL_T1`; quy tắc terminal `max(expires_at, completed_at + TTL_terminal)` cho `BE-TRF-05`; cấu hình thiếu/sai fail-closed; bản ghi còn tồn tại dù quá hạn vẫn replay/conflict; không purge `PENDING`. |
| `TRF-04.6` | Seam orchestrator và kích hoạt | `TransferOrchestrator` không mở outer transaction bao `T0–T2`; gọi `T1`/`T2` qua ranh giới transaction độc lập; trả `TransferCreateOutcome` cho boundary `BE-TRF-03`; chỉ đăng ký bean runtime khi ghép đủ provider và migration (`DEC-TRF-016`). |
| `TRF-04.7` | Kiểm thử và bằng chứng | Đạt bộ unit/fake consumer tests theo `05` §13.4; khi ghép tích hợp chạy kiểm thử PostgreSQL nhiều kết nối cho key race, challenge consume/rollback, audit fail-closed (`T-IDEM-01–06`, `T-STEP-01/02`, `CT-API-007–010`, `T-AUD-07`). |

## 4. Baseline canonical hash và idempotency

### 4.1. Canonical request hash (`07` §5.2, `02` §6.2)

Đối tượng JSON chuẩn hóa (mọi giá trị lá là chuỗi):

```json
{
  "customerId": "2001",
  "sourceAccountId": "3001",
  "destination": { "type": "ACCOUNT_NUMBER", "value": "0123456789" },
  "amount": "150000.00",
  "currency": "VND",
  "description": "Chuyen tien"
}
```

| Trường | Quy tắc chuẩn hóa |
|---|---|
| `customerId` | Lấy từ phiên xác thực phía server, không lấy từ body; chuỗi số thập phân. |
| `sourceAccountId` | Chuỗi số thập phân, không có số `0` đứng đầu. |
| `destination.type` | `ACCOUNT_ID` hoặc `ACCOUNT_NUMBER` — biểu diễn đích là một phần của yêu cầu gốc. |
| `destination.value` | `ACCOUNT_ID`: chuỗi ID; `ACCOUNT_NUMBER`: giữ nguyên chuỗi chữ số, **không bỏ số `0` đứng đầu**, cắt khoảng trắng hai đầu (`trim`). |
| `amount` | `BigDecimal.setScale(2)`, `toPlainString()`, bỏ số `0` đứng đầu; không dùng `double`, không tự làm tròn khi sai scale. |
| `currency` | Chữ hoa (`VND`). |
| `description` | `trim()`; `null` và chuỗi rỗng cùng chuẩn hóa thành `""`. |
| Tuần tự hóa & băm | RFC 8785 JCS (`JcsCanonicalizer`) → `lowercase_hex(SHA-256(UTF-8(JCS(payload))))`, lưu `CHAR(64)`. |
| Không đưa vào hash | Header, timestamp, correlation ID, thông tin thiết bị, và **`destinationAccountId` sau khi phân giải**. |

- Nếu client gửi `ACCOUNT_NUMBER` ở lần đầu và đổi sang `ACCOUNT_ID` ở lần thử lại cho cùng người nhận thì `request_hash` sẽ khác và trả về `409 IDEMPOTENCY_CONFLICT`.
- Không ghi log raw idempotency key, request body, hash hoặc PII; khi cần truy vết xung đột chỉ ghi mã băm HMAC fingerprint theo `DEC-TRF-012`.

### 4.2. Tra cứu, phân loại và replay (`07` §5.4, `DEC-TRF-004`)

Thứ tự xử lý: xác thực/phân quyền → kiểm tra schema/header → chuẩn hóa/tính hash → **tra cứu khóa toàn cục trên primary** → chỉ khi khóa chưa tồn tại mới phân giải tài khoản đích và kiểm tra quyền sở hữu/trạng thái/số dư/hạn mức. Đối chiếu quyền sở hữu khóa (`initiated_by_user_id` và `customer_id`) cùng `request_hash` trước khi trả trạng thái.

| Tình huống | Bản ghi `idempotency_record` | Phản hồi HTTP | Hiệu ứng |
|---|---|---|---|
| Khóa mới, payload hợp lệ | Chưa tồn tại | Tiếp tục preflight / `T0` / `T1` / `T2` | Tạo tối đa một giao dịch |
| Cùng actor/key/hash, đã kết thúc | `COMPLETED` / `FAILED` | Nguyên mã HTTP và body gốc (`201` / `422`) + `Idempotent-Replayed: true` | Không tạo giao dịch/bút toán/audit/thông báo mới |
| Cùng actor/key/hash, chưa kết thúc | `RECEIVED` / `IN_PROGRESS` | `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After` | Luồng HTTP không tự chạy lại `T2`; do worker nội bộ phục hồi (`BE-TRF-06`) |
| Cùng khóa nhưng khác hash hoặc khác actor | Bất kỳ | `409 IDEMPOTENCY_CONFLICT` | Ghi audit bảo mật `C3` best-effort + fallback log/metric; không lộ dữ liệu giao dịch gốc |
| Khóa đã bị purge sau khi hết hạn | Không còn | Có thể xử lý như yêu cầu mới | Client nên tra cứu `reference` trước nếu chưa rõ kết quả |
| Thiếu hoặc sai định dạng khóa | — | `400 VALIDATION_ERROR` | Từ chối ngay tại tầng boundary (`BE-TRF-03`) |
| Cần xác thực tăng cường (Step-up) | Chưa tạo bởi request này | `428 STEP_UP_REQUIRED` + thông tin challenge | Không tạo `transfer`, `idempotency_record`, `ledger_entry` hay `limit_reservation` |

- Khi phát lại (`replay`), thân phản hồi giữ nguyên `correlationId` gốc đã lưu, trong khi header `X-Correlation-ID` phản ánh request hiện tại (`DEC-TRF-004`).
- Khi preflight từ chối vì lý do nghiệp vụ (tài khoản không tồn tại/ngừng hoạt động, không đủ số dư, vượt hạn mức), hệ thống **đọc lại khóa trên primary trước khi trả lỗi**: nếu bản ghi đã xuất hiện thì phân loại replay/`409`/conflict; nếu vẫn vắng mặt mới trả lỗi preflight.

### 4.3. Thời hạn lưu trữ (`TTL`) và dọn dẹp (`DEC-TRF-005`, `DEC-TRF-013`)

- Tại `T1`, đọc snapshot `TTL_T1` hợp lệ (`>= 86400` giây) kèm phiên bản cấu hình (`ttl_t1_config_version`), đặt `expires_at = created_at + TTL_T1`. Khi giao dịch kết thúc tại `T2` (`BE-TRF-05`), đọc snapshot `TTL_terminal`, cập nhật nguyên tử `completed_at`, thân phản hồi đã che dấu PII, phiên bản cấu hình và `expires_at = GREATEST(expires_at, completed_at + TTL_terminal)`.
- Cấu hình thiếu hoặc không hợp lệ sẽ fail-closed. Bản ghi còn tồn tại trong DB dù đã qua `expires_at` vẫn được dùng để replay/conflict cho đến khi job dọn dẹp (`BE-TRF-06`) xóa an toàn; tuyệt đối không xóa bản ghi của giao dịch đang `PENDING`.

## 5. Step-up `T0` và Customer Guard / Reservation (`DEC-TRF-001/002/008/011`)

1. **Đánh giá dưới Customer Guard:** Lấy khóa bảo vệ theo khách hàng qua `Limit.lockStepUpGuard`; đọc mốc thời gian từ PostgreSQL primary để xác định ngày nghiệp vụ theo múi giờ `Asia/Ho_Chi_Minh`. Kiểm tra lại `idempotency_key` dưới guard. Công thức tổng dự kiến trong ngày:
   $$\text{Projected Daily Total} = \text{Used}_{\text{COMPLETED (today)}} + \sum \text{Reservation}_{\text{PENDING (active)}} + \text{Amount}_{\text{current}}$$
   Ngưỡng mặc định: giá trị một lệnh **`>= 10.000.000 VND`** hoặc tổng dự kiến trong ngày **`> 20.000.000 VND`**.
2. **Nhánh `428 STEP_UP_REQUIRED`:** Kết thúc giao dịch kiểm tra trước khi gọi cổng gửi OTP; yêu cầu nhận `428` không tạo `transfer`, `idempotency_record`, `ledger_entry` hay `limit_reservation`. Challenge do `identity` tạo trong giao dịch ngắn `T0`, gắn `userId`, `request_hash`, `idempotency_key` và `purpose = 'TRANSFER_STEP_UP'`; gửi OTP bên ngoài giao dịch DB sau khi commit và chỉ trả `428` sau khi gateway ACK và cập nhật `status = 'SENT'`. Nếu gửi OTP lỗi hoặc quá hạn chờ, trả `503 OTP_DISPATCH_FAILED` (fail-closed).
3. **Xác minh và tiêu thụ:** Client xác minh mã OTP qua `POST /api/v1/auth/step-up/verify` (`identity`). Chỉ challenge ở trạng thái `SENT` mới được xác minh (TTL 5 phút, tối đa 5 lần thử, gửi lại tối đa 3 lần với thời gian chờ 60 giây). Khi client gửi lại lệnh chuyển tiền cùng `Idempotency-Key`, pha `T1` tiêu thụ nguyên tử challenge `VERIFIED` còn hạn (`consumed_at = clock_timestamp()`). Nếu `T1` bị rollback, trạng thái tiêu thụ challenge được hoàn lại.

## 6. Pha `T1` — Ghi nhận Ý định Giao dịch Bền vững (`02` §5.2, `05` §10.1, `DEC-TRF-017`)

Thực thi trong một giao dịch ngắn độc lập (`REQUIRES_NEW`, `READ_COMMITTED`), không có giao dịch bao ngoài `T0–T2`. Thứ tự các bước trong `T1`:

1. Lấy `customer step-up guard`; kiểm tra lại `idempotency_key` bằng snapshot mới nhất; đánh giá lại điều kiện step-up (§5).
2. Sinh mã tham chiếu `reference_no` (`^TXN[0-9]{8}[A-Z0-9]{8,20}$`) và chèn bản ghi `banking.transfer` ở trạng thái `PENDING` với `step_up_challenge_id = NULL`, lưu đầy đủ ngữ cảnh bất biến (`customer_id`, `initiated_by_user_id`, `source_account_id`, `destination_account_id`, `amount`, `currency`, `description`, `correlation_id`).
3. Chèn `banking.idempotency_record` (`status = 'RECEIVED'`, `request_hash`, `transfer_id`, `ttl_t1_seconds`, `ttl_t1_config_version`, `expires_at`) bằng `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING`. Nếu không trả về dòng nào (thua cuộc đua unique), ném ngoại lệ nội bộ để **rollback toàn bộ `T1`** (bao gồm cả dòng `transfer` vừa chèn), sau đó mở giao dịch mới đọc lại bản ghi thắng cuộc để trả replay hoặc `409`.
4. Tạo bản ghi giữ chỗ hạn mức (`limit_reservation`) qua `Limit.reserveForPendingTransfer`.
5. Nếu giao dịch yêu cầu step-up: gọi `StepUpChallengePort.consumeVerified` với UUID `challengeRef` để kiểm tra binding và đánh dấu `consumed_at`, nhận về khóa nội bộ `challengeId (BIGINT)` rồi cập nhật `transfer.step_up_challenge_id` (`DEC-TRF-017`). Nếu challenge không khớp hoặc đã hết hạn/đã dùng, rollback toàn bộ `T1` và trả `409 STEP_UP_CHALLENGE_MISMATCH` hoặc `410 AUTH_OTP_EXPIRED`.
6. Thực hiện `flush` toàn bộ thay đổi nghiệp vụ; gọi `AuditModuleApi.recordInTransaction` (`MANDATORY`) để ghi sự kiện `TRANSFER_CREATED` (`C1`) ở bước cuối cùng trước khi commit. Nếu ghi audit thất bại, rollback toàn bộ `T1` và trả `500 AUDIT_WRITE_FAILED`.
7. Commit `T1`. Pha `T1` **không** khóa tài khoản, không đọc/cập nhật số dư và không ghi bút toán sổ cái. Sau khi `T1` commit, `TransferCreateOrchestrator` gọi tiếp seam `settleCreated` (`BE-TRF-05`) để thực thi `T2`.

## 7. Kế hoạch kiểm thử và bằng chứng

| Nhóm kiểm thử | Thiết lập và điều kiện nghiệm thu |
|---|---|
| Unit test vector băm (`TransferRequestHashTest`) | Thứ tự trường JSON, scale/leading zero của `amount`, `description` `null`/rỗng/khoảng trắng, `destination.type`/`value`; vector cố định 64 ký tự hex chữ thường; đảm bảo `toString()` che dấu dữ liệu nhạy cảm. |
| Phân loại idempotency & TTL (`IdempotencyDecisionTest`, `IdempotencyRetentionTest`) | Phân loại `NEW`/`REPLAY`/`IN_PROGRESS`/`CONFLICT`, hai user cùng customer vẫn conflict, kiểm tra quyền sở hữu trước trạng thái; từ chối cấu hình TTL `< 24h`, không rút ngắn `expires_at`, chỉ cho phép purge bản ghi terminal đã hết hạn. |
| Candidate Preflight & `T1` (`TransferPreflightCandidateTest`, `TransferIntentCandidateTest`) | 13 tests preflight + 14 tests `T1` + 5 tests `TransferCreateOrchestrator`/`StepUpChallengePort` với fake theo `05` §13.4: tra cứu trước preflight, đọc lại khi từ chối, gắn kết UUID→BIGINT challenge, rollback khi thua unique hoặc khi lỗi audit/challenge. |
| Biên ngưỡng Step-up (`StepUpAmountPolicyTest`, `T-STEP-01/02`) | Biên đơn lệnh `9.999.999,99` vs `10.000.000,00`; biên tổng ngày `20.000.000,00` vs `20.000.000,01`; hai khóa khác nhau đồng thời cộng dồn vượt ngưỡng ngày dưới customer guard; tính cả reservation `PENDING` từ ngày trước. |
| Kiểm thử tích hợp PostgreSQL nhiều kết nối (khi ghép provider thật) | Đua `Idempotency-Key` đồng thời (`T-IDEM-01`), đua tiêu thụ cùng challenge (`T-STEP-01`), rollback `T1` hoàn lại trạng thái challenge và reservation, lỗi ghi audit `TRANSFER_CREATED` làm rollback toàn bộ `T1` (`T-AUD-07`), và bộ kiểm thử hợp đồng `CT-API-007–010`. |

## 8. Vấn đề phối hợp liên module khi tích hợp

| Hạng mục | Đầu mối | Trạng thái & hành động khi ghép tích hợp |
|---|---|---|
| Ghép nối các provider thật (`Identity`, `Limit`, `Account`, `Audit`) | Đạt, Vũ, Thịnh | Các port và candidate phía `transfer` đã hoàn tất (`DEC-TRF-016/017`); khi ghép nhánh cần nối adapter thật và chạy bộ kiểm thử PostgreSQL nhiều kết nối. |
| Chuỗi миграция Flyway production (`V7`) | Phương, Vũ | Chuyển `db/transfer-candidate/V2__transfer.sql` sang `V7__create_transfer_and_idempotency_tables.sql` theo thứ tự chuẩn `DEC-COM-001`. |

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v1.0` | 16/11/2026 | Đặc tả phạm vi, canonical hash, idempotency, TTL, step-up `T0`, `T1` insert-first, danh mục kiểm thử và hiện trạng candidate `BE-TRF-04`. |
| `v1.1` | 17/11/2026 | Triển khai seam `TransferCreateOrchestrator`, đóng khoảng trống gắn kết UUID→BIGINT challenge trong `T1` (`DEC-TRF-017`), bổ sung `StepUpChallengePort`, `TransferReferenceGenerator` và kiểm thử fail-closed TTL (366 tests backend / 163 tests Transfer PASS). |
| `v1.2` | 18/11/2026 | Giải quyết xung đột merge, đồng bộ quyết định `DEC-DOC-001`, `DEC-COM-001`, `DEC-TRF-008/017` và chuẩn hóa văn phong kỹ thuật. |
