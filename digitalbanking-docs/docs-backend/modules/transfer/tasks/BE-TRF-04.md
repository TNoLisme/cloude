# BE-TRF-04 — Idempotency, step-up và T1

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-04`
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** In progress / Blocked — primitive hash/classification/step-up/retention, preflight/T1/T2 candidate và seam orchestrator (`TransferCreateOrchestrator`) đã triển khai và test bằng fake; boundary challenge UUID đã đóng gap `05` §13.4 trong candidate; port T0 `StepUpChallengePort` và `TransferReferenceGenerator` đã có. Chưa nối runtime production. Provider thật (Identity challenge, Limit guard/reservation, Account resolve, Audit C1), bằng chứng PostgreSQL cho guard/key race/challenge/rollback, replay HTTP end-to-end và review DEC-TRF-008 chưa hoàn tất.
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, Identity (Đạt), Account/Customer (Đạt), Limit (Vũ), Audit (Thịnh), Common (Phương), DBA/Architect, API owner, QA và Security theo phạm vi.

## 1. Mục tiêu và phạm vi

Xây lớp idempotency và intent bền vững cho POST transfer: chuẩn hóa payload và tính canonical hash bằng JCS/SHA common; tra key global và phân loại replay/conflict/in-progress theo `07` §5; điều phối step-up T0 (challenge bind user/hash/key, dispatch ngoài transaction) và đánh giá ngưỡng dưới guard customer theo DEC-TRF-008; thực hiện T1 tạo transfer `PENDING` + idempotency `RECEIVED` + reservation + consume challenge + audit `TRANSFER_CREATED` trong một transaction riêng, insert-first với unique global là trọng tài. Task nối seam `TransferOrchestrator` để boundary BE-TRF-03 map outcome vào HTTP mà không đổi contract.

Ngoài phạm vi: boundary HTTP/validation/authorization (BE-TRF-03 đã có candidate); T2 money/ledger/limit/fraud/audit C2-C1/outbox/terminal (BE-TRF-05); recovery worker, purge job và reconciliation (BE-TRF-06); telemetry/notification wiring (BE-TRF-07). Task này không tự chạy T2 từ HTTP retry, không giữ transaction qua OTP/network, không viết lại JCS/error stack/session của common, không nhận actor/customer từ body. Chuẩn bị test consumer bằng fake theo `05` §13.4 khi provider chưa có; fake không thay PostgreSQL thật và provider thật để nghiệm thu lock/unique/rollback.

**Hiện trạng 17/11/2026:** các primitive `TransferRequestHash` (tự hash payload immutable, `toString` redacted), `IdempotencyDecision` (phân loại NEW/REPLAY/IN_PROGRESS/CONFLICT, kiểm actor/customer/hash trước khi lộ trạng thái), `StepUpAmountPolicy` (ngưỡng thuần ≥10 triệu/lệnh, >20 triệu/ngày), `IdempotencyRetention` (snapshot TTL ≥24h, expiry ban đầu/terminal, điều kiện purge) và `TransferReferenceGenerator` (đề xuất OQ-02, SecureRandom). `TransferPreflightCandidate` có 13 test fake: lookup key trước preflight, đọc lại khi bị từ chối, xác minh binding command T1, `ChallengeRequired` mang requestHash, `Settlement` receipt nhất quán outcome; resolution số tài khoản ủy quyền port Account. `TransferIntentCandidate` có 14 test fake: transaction `REQUIRES_NEW` + `READ_COMMITTED`, guard → recheck key → đánh giá step-up → insert transfer/key (FK challenge NULL) → reservation → consume UUID → attach FK BIGINT → flush → audit CREATED cuối; thua unique key rollback toàn T1 rồi đọc lại trong transaction mới; `ChallengeRejectedException` (mismatch/expired) và `AuditWriteException` rollback toàn T1; TTL snapshot fail-closed. **Gap UUID/BIGINT `05` §13.4 đã đóng trong candidate (DEC-TRF-017):** command giữ `verifiedChallengeRef` UUID, không suy UUID→ID. `TransferCreateOrchestrator` (candidate, chưa đăng ký) hiện thực seam `TransferOrchestrator` cho boundary 03: map outcome, T0 `requestChallenge` sau gate, map lỗi 409/410/500/503; `StepUpChallengePort` consumer ánh xạ TRFC-ID-02/05 (5 test). Lượt `./gradlew check` sau BE-TRF-04 đạt **366 test backend, 163 test Transfer**, 0 failure/error/skip. **Chưa đạt/Blocked:** provider adapter thật và wiring preflight (findVerified/ownership/TTL config); proof PostgreSQL guard/key race, challenge consume/rollback, audit CREATED fail-closed; replay HTTP và 428 end-to-end (cần 03/05 kích hoạt); review protocol DEC-TRF-008; format reference OQ-02; policy PII description/key; trường hiển thị 428 của `ChallengeIssued` chờ Identity review. Xem [evidence BE-TRF-04/05](evidence/BE-TRF-04-05-candidate.md).

## 2. Nguồn và dependency

- [[digitalbanking-docs/docs-common/07. API contracts.md]] §5 (5.1–5.5), §6 (6.1–6.6), §8.3.1, §4.3: idempotency, canonical hash, step-up, lỗi và quy tắc xử lý; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]: hợp đồng máy đọc cần đối chiếu.
- [[digitalbanking-docs/docs-common/03. SRS.md]] §2.13, AC-010/011 (replay/conflict), AC-033 (428 không tạo record, xử lý một lần), AC-018/034 (audit); [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.1/§4.4.3 (transfer/idempotency), §4.2.5 (unique active challenge).
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5.1–§5.2, §6, §7.3/§7.7, §11.4, Appendix A.6–A.7/B.2–B.3: preflight/T0, T1 insert-first, lock order và challenge; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §5.2, §8, §10.1, §11.1, §13.4: challenge SPI, use case Transfer, guard/reservation, audit và baseline v1.
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6–§5.7: PII allowlist và sự kiện không ghi audit; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §6, §11–§12: convention application/transaction và kiểm thử; [Thiết kế Transfer](../design.md) §2–§5; [backlog](README.md) §2; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-001/002/004/005/008/011/012/013/015/016.
- [[digitalbanking-docs/docs-common/09. Test plan.md]] §7.3–§7.4.1, §10: oracle `T-IDEM-*`, `T-STEP-*`, `CT-API-007–010` và evidence. `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend; `07` là căn cứ ngữ nghĩa API. Mâu thuẫn nguồn chuyển owner/điều hành phân xử, không tự suy diễn sign-off.

| Dependency | Cần cho BE-TRF-04 | Trạng thái / cách xử lý |
|---|---|---|
| BE-TRF-01 | Baseline contract v1 (DEC-TRF-015), oracle idempotency/step-up và quyết định replay/TTL | Baseline `05` §13.4 dùng được cho phát triển độc lập; review liên module và evidence integration vẫn Blocked. |
| BE-TRF-02 | Schema/migration `transfer`, `idempotency_record` (TTL version, challenge FK, unique) và persistence primitives | Migration/JPA candidate ngoài Flyway production; T1 trên DB thật cần migration được review và FK provider. |
| BE-TRF-03 | Boundary HTTP, seam `TransferOrchestrator` và mapping outcome `TransferCreateOutcome` | Candidate đã có; end-to-end POST/replay/428 chỉ nghiệm thu khi 04/05 hoàn tất và được kích hoạt theo DEC-TRF-016. |
| Identity — Đạt | `requestChallenge`/`findVerifiedBindingIfPresent`/`consumeVerified` (TRFC-ID-02/05/04), session thật; challenge bind user/hash/key, single-use, expiry | `api` chưa có runtime; fake test/dev theo `05` §5.2/§13.4. Gap UUID `challengeRef` ↔ BIGINT `challengeId` phải giải quyết trước activation; provider thật + test consume race/rollback là điều kiện nghiệm thu. |
| Account/Customer — Đạt | `resolveDestination` (TRFC-AC-01), `assertOwnership` (TRFC-AC-02) cho preflight; blind index Account-owned | Fake cùng vector `05` §13.4; resolution/ownership thật và IDOR là điều kiện nghiệm thu. |
| Limit — Vũ | `lockStepUpGuard`/`evaluateStepUpUnderGuard`/`reserveForPendingTransfer` (TRFC-LI-04/05); guard customer, reservation PENDING, business date/version | Contract thiết kế đã chốt (DEC-TRF-008/015), runtime chưa có; test fake rồi PostgreSQL + provider thật cho race/atomicity. |
| Audit — Thịnh | `recordInTransaction` C1 `TRANSFER_CREATED` JOIN/MANDATORY, fail-closed (TRFC-AU-01) | API thiết kế; lỗi audit phải rollback toàn T1. Provider thật + `T-AUD-07` là điều kiện nghiệm thu. |
| Common — Phương | `JcsCanonicalizer`, `HmacService` (SHA-256), `Clock`, request context, Problem/error catalog | Đã có code; tái sử dụng, không viết lại. Integration với identity/transfer thật chưa được chứng minh. |
| BE-TRF-05 | Seam `settleCreated` (T2) sau T1 commit; terminal hóa idempotency/response | Candidate chưa đăng ký; 04 chỉ gọi seam, không thực hiện money path. |
| API/FE/QA, DBA/Architect, Security | Review replay correlation (OI-CON-13), format reference OQ-02, protocol DEC-TRF-008, migration TTL version | Chưa review; thiếu review/evidence giữ AC tương ứng Blocked, không tự sign-off. |

Giao tiếp mới chưa có trong `05`: nêu phương án thay thế qua public API/DTO của owner, ghi đề xuất vào `05` để review rồi test fake theo hợp đồng đó; không truy cập repository/entity nội bộ module khác, không tạo bean fake trong production.

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / điều kiện nghiệm thu |
|---|---|---|
| TRF-04.1 | Canonical hash và test vector. | Chuẩn hóa đúng §4.1 theo `07` §5.2/`02` §6.2; JCS + SHA-256 common, lowercase hex 64; vector cố định cho field order, scale amount, null/rỗng, destination type, leading zero; hash không đổi khi retry cùng payload, đổi khi đổi representation; không log raw key/body/PII. Vector được chốt và dùng chung provider/fake. |
| TRF-04.2 | Lookup, phân loại và replay idempotency. | Tra key global trên primary sau auth/schema/hash, **trước** resolve/đọc account; đối chiếu actor server-side với `initiated_by_user_id` + customer + hash trước khi lộ trạng thái; terminal replay đúng status/body gốc + `Idempotent-Replayed: true`; nonterminal 409 + `Retry-After`, HTTP không chạy T2; conflict 409 + C3 best-effort, không lộ dữ liệu actor khác; preflight reject → đọc lại key; DB error khác absent. |
| TRF-04.3 | Step-up T0 và guard/reservation. | Đánh giá dưới guard customer theo DEC-TRF-008: completed ngày hiện tại + mọi PENDING reservation kể cả ngày trước + lệnh mới, mỗi lệnh một lần; ngưỡng cấu hình ≥10 triệu/lệnh hoặc >20 triệu/ngày, lưu policy/version; recheck key dưới guard; nhánh 428 không tạo transfer/idempotency/ledger/reservation bởi request đó; challenge bind user/hash/key, dispatch sau commit, chỉ trả 428 sau ACK + SENT persist; không giữ lock qua OTP. |
| TRF-04.4 | T1 intent bền vững. | Một transaction riêng: guard → recheck → insert transfer PENDING (sinh reference) → insert key `ON CONFLICT DO NOTHING RETURNING` → reservation → consume challenge VERIFIED đúng binding, một lần → cập nhật FK challenge → flush → audit CREATED cuối; thua unique rollback toàn T1 trước khi đọc lại trong transaction mới, không orphan; audit lỗi rollback T1; T1 commit để lại PENDING/RECEIVED khi T2 chưa chạy; không khóa account/đọc balance/ghi ledger trong T1. |
| TRF-04.5 | TTL và retention. | T1 snapshot `TTL_T1` hợp lệ ≥24h + version, `expires_at = created_at + TTL_T1`; quy tắc terminal snapshot `TTL_terminal` và `max(expires_at, completed_at + TTL_terminal)` định nghĩa sẵn cho 05; config thiếu/sai fail-closed; record còn tồn tại dù quá expiry vẫn replay/conflict; không purge PENDING (job purge thuộc 06). |
| TRF-04.6 | Seam orchestrator và kích hoạt. | `TransferOrchestrator` không outer transaction bao T0–T2; gọi T1/T2 qua transaction boundary thật; outcome đúng `TransferCreateOutcome` cho 03 map HTTP; chỉ đăng ký runtime khi 04/05 + provider thật + migration đã review sẵn sàng (DEC-TRF-016); giải quyết gap UUID/BIGINT của challenge trước activation. |
| TRF-04.7 | Kiểm thử và evidence. | Test §7 đạt: vector hash, classification, fake consumer theo `05` §13.4; PostgreSQL nhiều connection cho key race/challenge/rollback/audit fail với provider thật khi nghiệm thu; `T-IDEM-01–06` (phần T1), `T-STEP-01/02`, `CT-API-007–010`, `T-AUD-07`; evidence theo `09` §10; thiếu dependency/môi trường ghi Blocked, không đánh dấu Verified bằng mock. |

## 4. Baseline canonical hash và idempotency

### 4.1. Canonical request hash (`07` §5.2, `02` §6.2)

Object chuẩn hóa (mọi giá trị là chuỗi):

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

| Field | Quy tắc chuẩn hóa |
|---|---|
| `customerId` | Lấy từ session server-side, không từ body; chuỗi thập phân. |
| `sourceAccountId` | Chuỗi thập phân, không leading zero. |
| `destination.type` | `ACCOUNT_ID` hoặc `ACCOUNT_NUMBER` — representation là một phần của request. |
| `destination.value` | `ACCOUNT_ID`: chuỗi ID; `ACCOUNT_NUMBER`: giữ nguyên chuỗi số, **không bỏ leading zero**, trim. |
| `amount` | `BigDecimal.setScale(2)`, `toPlainString()`, bỏ leading zero; không `double`, không làm tròn. |
| `currency` | Uppercase; MVP chỉ `VND`. |
| `description` | Trim; `null` và rỗng cùng thành `""`. Chuẩn hóa Unicode NFC là **đề xuất** trong `02`, chưa chốt — không tự thêm, xác nhận với API owner trước khi đóng băng vector. |
| Serialize/hash | RFC 8785 JCS (common `JcsCanonicalizer`) → `lowercase_hex(SHA-256(UTF-8(JCS(payload))))`, lưu `CHAR(64)`. |
| Không đưa vào hash | Header, timestamp, correlation ID, device info, **resolved destination ID**. |

- Client gửi `ACCOUNT_NUMBER` lần đầu và `ACCOUNT_ID` lần retry cho cùng người nhận → hash khác → `IDEMPOTENCY_CONFLICT`. Đây là hành vi có chủ đích; client phải retry đúng payload.
- Vector tối thiểu: cùng payload khác thứ tự field → cùng hash; `150000.00` vs `150000.0` vs `0150000.00` → cùng hash sau chuẩn hóa; description `null`/`""`/khoảng trắng → cùng hash; đổi `destination.type` hoặc value → hash khác; amount sai scale bị từ chối trước hash, không tự làm tròn. Vector dùng synthetic, không chứa PII; đối chiếu chữ ký `TransferRequestHash`/`TransferAmount` hiện có.
- Không log raw key, body, hash hay PII; cần điều tra thì log fingerprint HMAC theo DEC-TRF-012. DTO/command `toString` redacted.

### 4.2. Lookup, phân loại và replay (`07` §5.4, DEC-TRF-004)

Thứ tự: authenticate/authorize → validate schema/header → chuẩn hóa/hash → **tra key global trên primary** → chỉ với key vắng mới resolve destination/kiểm ownership/status/balance/limit. Kiểm actor trước trạng thái: so `userId` server-side với `transfer.initiated_by_user_id` **và** customer scope; hai user cùng customer dùng cùng key/hash vẫn conflict.

| Tình huống | Record | Response | Hiệu ứng |
|---|---|---|---|
| Key mới, payload hợp lệ | Không có | Tiếp tục preflight/T0/T1/T2 | Một transfer |
| Cùng actor/key/hash, terminal | `COMPLETED`/`FAILED` | Đúng HTTP status/body gốc (`201`/`422`) + `Idempotent-Replayed: true` | Không transfer/debit/audit/usage/notification mới; không đánh giá lại risk/step-up/limit/fraud |
| Cùng actor/key/hash, chưa terminal | `RECEIVED`/`IN_PROGRESS` | `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`, kể cả stale | HTTP không tự chạy T2; worker nội bộ recovery (BE-TRF-06) |
| Cùng key, khác hash hoặc khác actor | Bất kỳ | `409 IDEMPOTENCY_CONFLICT` | Không tạo gì; C3 best-effort + fallback security log/metric; không lộ chủ key hay response actor khác |
| Key đã purge | Không còn | Có thể coi là request mới | Client query reference/liên hệ Ops trước nếu chưa rõ outcome; không hứa chống trùng sau purge |
| Thiếu/sai key | — | `400 VALIDATION_ERROR` | Không xử lý (boundary 03) |
| Cần step-up | Chưa có record do request này | `428 STEP_UP_REQUIRED` + challenge | Không tạo transfer/idempotency/ledger bởi request đó |

- Replay body giữ `correlationId` gốc; header `X-Correlation-ID` phản ánh request hiện tại (DEC-TRF-004, OI-CON-13 — cần API/FE/QA review). Body lưu đã mask; không "làm giàu" body cũ từ DB; không lưu lỗi transient (`500/503/409`) làm terminal replay.
- Preflight từ chối nghiệp vụ (account not-found/inactive, insufficient, limit) → **đọc lại key trên primary trước khi trả lỗi**: record đã xuất hiện → xử lý replay/409/conflict; vẫn vắng → trả lỗi preflight, tuyến tính hóa tại lần đọc vắng. Lỗi DB không coi là vắng.
- `IdempotencyDecision.classify` hiện kiểm ownership trước trạng thái và redacted `toString`; lookup lỗi phải propagate, không biểu diễn bằng `Optional.empty`.

### 4.3. TTL và retention (DEC-TRF-005/013)

- T1 đọc snapshot `TTL_T1` hợp lệ ≥86400 giây + version (chuỗi ≤128 ký tự), đặt `expires_at = created_at + TTL_T1`; lưu cả version. Transaction terminal (05) đọc snapshot `TTL_terminal` riêng, ghi atomic `completed_at`, response masked, version và `expires_at = max(expires_at hiện có, completed_at + TTL_terminal)`; giảm TTL không rút ngắn expiry.
- Config thiếu/không đọc được/không hợp lệ → fail-closed, không commit terminal dựa trên giá trị đoán. TTL đo từ mốc DB lưu, không phải lúc client nhận response.
- Record còn tồn tại dù quá expiry vẫn replay/conflict; chỉ purge terminal đã hết hạn sau lock/recheck (job thuộc BE-TRF-06); không purge PENDING kể cả quá TTL. `IdempotencyRetention` chỉ tính toán, không tự xóa.

## 5. Step-up T0 và guard/reservation (DEC-TRF-001/002/008/011)

1. **Đánh giá dưới guard:** lấy guard duy nhất theo customer (không chia ngày/version) qua Limit `lockStepUpGuard`; gate chụp mốc thời gian từ PostgreSQL primary sau khi giữ guard để tính business date (`Asia/Ho_Chi_Minh` cấu hình). Recheck key bằng fresh snapshot dưới guard. Tổng dự kiến = COMPLETED của customer trong ngày nghiệp vụ hiện tại + **mọi** reservation PENDING còn hiệu lực kể cả ngày trước + lệnh đang xét; mỗi lệnh một lần, không cộng trùng counter customer/account. Ngưỡng mặc định: amount một lệnh **≥ 10.000.000 VND** hoặc tổng dự kiến **> 20.000.000 VND**; hai điều kiện độc lập, cấu hình được, lưu policy/version; các trigger rủi ro khác vẫn độc lập. Thiếu config/evidence hoặc lỗi đọc → fail-closed, không mặc định cho qua.
2. **Nhánh 428:** kết thúc transaction gate trước khi dispatch; request này không tạo transfer/idempotency/ledger/reservation. Challenge do Identity tạo trong OWN T0, bind `userId`/`customerId`, `request_hash`, `idempotency_key`, purpose `TRANSFER_STEP_UP`; dispatch OTP ngoài transaction sau commit; chỉ trả 428 sau gateway ACK và persist `SENT` đúng generation; dispatch fail/timeout/unknown → `503 OTP_DISPATCH_FAILED`, invalidate khi có thể, DB lỗi fail-closed. Không giữ lock/transaction chờ OTP.
3. **Đồng thời:** request khác cùng key có thể commit T1 trong lúc challenge dispatch/response; không suy DB toàn cục trống từ 428. Retry cùng key/payload kiểm idempotency trước; challenge dư hết hạn/vô hiệu an toàn, không tạo intent hay consume lần hai. Unique active `(idempotency_key, request_hash)` của Identity là chốt cuối; reissue phải khóa/đánh dấu challenge cũ hết hạn trước insert, không revive challenge đã consume.
4. **Verify và consume:** client verify qua `POST /api/v1/auth/step-up/verify` (Identity); chỉ challenge `SENT` đúng generation đã ACK mới verify được; TTL 5 phút, resend tối đa 3, cooldown 60 giây theo `07`/DEC-TRF-011. T1 consume `VERIFIED` chưa hết hạn, đúng binding, **một lần** dưới lock; rollback T1 hoàn lại consume; T2 không verify lại chỉ vì challenge hết hạn sau T1 commit. Challenge hết hạn trước T1 → từ chối, client step-up lại.
5. **Lỗi step-up** map theo `07` §6.6: OTP sai `401 AUTH_OTP_INVALID`; hết hạn `410 AUTH_OTP_EXPIRED`; vượt attempt `423 AUTH_CHALLENGE_LOCKED`; không khớp binding `409 STEP_UP_CHALLENGE_MISMATCH`; không thuộc user `404/403`; gateway lỗi `503 OTP_DISPATCH_FAILED`; retry không có challenge VERIFIED → `428`.

## 6. T1 — Intent bền vững (`02` §5.2, `05` §10.1)

Một transaction riêng (`REQUIRES_NEW`, `READ_COMMITTED`), không outer transaction bao T0–T2; thứ tự trong transaction:

1. Giữ guard customer; recheck idempotency bằng fresh snapshot; đánh giá step-up (§5). Cần challenge mà chưa VERIFIED → nhả guard/rollback gate, trả 428 sau dispatch.
2. Sinh reference (format đề xuất `^TXN[0-9]{8}[A-Z0-9]{8,20}$`, OQ-02 chờ API owner chốt) và insert `banking.transfer` `PENDING` với `step_up_challenge_id` NULL; lưu immutable context (customer/initiator/source/destination đã resolve/amount/currency/description/correlation).
3. Insert `banking.idempotency_record` (`RECEIVED`, `request_hash`, `transfer_id`, TTL/version) bằng `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING`; không trả row → throw exception riêng để **rollback toàn bộ T1** kể cả transfer vừa insert. Orchestrator bắt ngoài transaction, đọc lại record trong transaction mới và trả replay/409; không orphan, không catch rồi tiếp tục trong transaction đã lỗi.
4. Tạo reservation per-transfer qua Limit `reserveForPendingTransfer` (một reservation/transfer, lưu mode/version/ngày T1 để trace); lỗi/unique thua rollback toàn T1.
5. Nếu có step-up: consume challenge VERIFIED qua Identity `consumeVerified` (lock row, kiểm user/purpose/hash/key/status/expiry dưới lock); Identity trả `challengeId` BIGINT → cập nhật `transfer.step_up_challenge_id` (partial unique là chốt cuối) trước flush/audit/commit. **Gap `05` §13.4 đã đóng trong candidate (DEC-TRF-017):** command giữ UUID `challengeRef` snapshot, insert FK NULL rồi attach BIGINT sau consume, không suy UUID→ID, không đọc bảng Identity; provider thật + test consume race/FK/rollback vẫn là điều kiện nghiệm thu. Mismatch/expired/đã dùng → rollback T1, trả `409 STEP_UP_CHALLENGE_MISMATCH`/`410`.
6. Flush toàn bộ business writes; gọi audit C1 `TRANSFER_CREATED` qua `recordInTransaction` JOIN/MANDATORY — thao tác cuối trước commit; lỗi audit rollback toàn T1, không transfer/idempotency, trả `500 AUDIT_WRITE_FAILED`/`503` (T-AUD-07); retry cùng key khi đó là request mới.
7. Commit. T1 **không** khóa account, không đọc/ghi balance, không ghi ledger. T1 commit để lại `PENDING`/`RECEIVED` khi T2 chưa chạy; `response_status`/`response_body` null đến terminal.

| Race | Hành vi |
|---|---|
| Hai request cùng customer cùng key | Guard tuần tự; bên sau recheck thấy record → 409/replay, không tạo transfer mới. |
| Hai request khác scope/customer cùng key | Unique global là trọng tài; bên thua rollback toàn T1 (transfer/reservation/consume) rồi đọc lại ngoài transaction. |
| Collision `reference_no` hoặc `step_up_challenge_id` | Lỗi unique riêng, không xử lý như replay key; không blanket-map unique thành 409. |
| Audit CREATED lỗi | Rollback toàn T1; không side effect; retry cùng key là request mới. |
| T1 commit nhưng response mất | Retry cùng key/hash thấy nonterminal → 409 + `Retry-After`; worker nội bộ recovery (06), không đoán terminal. |

Sau T1 commit, orchestrator gọi seam T2 `settleCreated` (BE-TRF-05) — claim fence bền và chỉ trả sau T2 commit; unknown commit propagate, không suy đoán. HTTP adapter (03) map outcome; kích hoạt production chỉ khi 04/05 + provider thật + migration đã review (DEC-TRF-016).

## 7. Kế hoạch kiểm thử và evidence

| Nhóm / liên kết | Thiết lập và oracle tối thiểu |
|---|---|
| Hash vector unit | `TransferRequestHashTest` mở rộng theo §4.1: field order, scale/leading zero amount, null/rỗng/trim description, destination type/value, NFC (chỉ khi được duyệt); vector cố định lowercase hex 64; assert không log raw key/body/PII. |
| Classification/retention unit | `IdempotencyDecisionTest`: NEW/REPLAY/IN_PROGRESS/CONFLICT, hai user cùng customer conflict, ownership trước trạng thái, lookup lỗi propagate; `IdempotencyRetentionTest`: TTL <24h bị từ chối, expiry không rút ngắn, purge chỉ terminal hết hạn. |
| Preflight/T1 fake | `TransferPreflightCandidateTest` (11) + `TransferIntentCandidateTest` (10) hiện có: lookup trước preflight, reread khi reject, binding command, guard/recheck, challenge, rollback giả lập, đọc lại sau rollback; bổ sung vector `05` §13.4 khi contract chốt. Fake không chứng minh lock/unique/rollback thật. |
| Step-up boundary | `9.999.999,99` không trigger đơn lệnh; `10.000.000,00` trigger; tổng ngày `20.000.000,00` không trigger, `20.000.000,01` trigger (amount từng lệnh <10 triệu); hai key khác nhau cùng dưới ngưỡng nhưng cộng vượt → không bypass (`T-STEP-02`, DEC-TRF-008); PENDING ngày trước vẫn tính; config đổi/version. |
| T1 race PostgreSQL | Nhiều connection độc lập + barrier: cùng key nhiều request → một transfer/một record, loser rollback không orphan (`T-IDEM-01` phần T1); challenge consume hai transaction chỉ một thắng, rollback T1 hoàn lại consume (`T-STEP-01`); challenge expired/mismatch; audit CREATED fail rollback toàn T1 (`T-AUD-07`); crash sau T1 → PENDING/RECEIVED, retry 409, worker resume an toàn (`T-IDEM-05`, phối hợp 06). Chỉ provider thật + PostgreSQL chứng minh; fixture synthetic không thay. |
| Idempotency hành vi | `T-IDEM-02` conflict khác hash/actor + C3, không lộ dữ liệu; `T-IDEM-03` replay 201/422 nguyên status/body + header, body correlation gốc/header hiện tại; `T-IDEM-04` nonterminal 409 + `Retry-After`, HTTP không chạy T2; `T-IDEM-06` cross-customer không replay. Replay end-to-end cần 03/05 kích hoạt. |
| Contract HTTP | `CT-API-007` replay thắng preflight (kể cả đích đã inactive); `CT-API-008` conflict; `CT-API-009` concurrent cùng key; `CT-API-010` 428 + verify retry một lần — chạy với boundary 03 và orchestrator thật; đối chiếu YAML, CI contract diff. |
| TTL/lifecycle | `T-IDEM-03/05` phần TTL: 24→48h/48→24h khi PENDING, expiry không ngắn lại, config thiếu/sai fail-closed, version được ghi; PENDING quá TTL không purge; race purge/retry thuộc 06/08. |
| Leak/boundary | Log/Problem/audit không chứa raw key, hash, body, OTP, số tài khoản đầy đủ; `toString` redacted; ArchUnit consumer-only-api kèm fixture âm; `./gradlew check` đạt. |

Mỗi bằng chứng theo `09` §10: test ID/phạm vi, revision, lệnh, môi trường, output và artifact synthetic không PII/secret, người chạy và reviewer. Test fake/fixture không gọi là end-to-end; thiếu provider thật, migration production hoặc môi trường ghi **Blocked** kèm owner. Kết quả unit/fake hiện có là evidence độc lập, không thay proof PostgreSQL/contract/integration.

## 8. Phối hợp, vấn đề mở và điều kiện đóng

| Vấn đề | Đầu mối | Ảnh hưởng / việc cần làm |
|---|---|---|
| Review protocol DEC-TRF-008 (guard/reservation, race 428, qua ngày) | Identity/Limit/Transfer/Architect/BA/Security/QA | Quyết định thiết kế đã có nhưng chưa review/chứng minh; release money path **Blocked** đến khi có review + evidence PostgreSQL race/midnight/config. |
| Challenge UUID ↔ BIGINT | Identity/Transfer | **Đã đóng trong candidate (DEC-TRF-017):** command giữ UUID snapshot, insert FK NULL → consume UUID → attach BIGINT trước flush/audit; `ChallengeRejectedException` rollback toàn T1. Còn thiếu provider thật + test wrong binding/consume race/FK/rollback PostgreSQL trước activation; trường hiển thị `ChallengeIssued` chờ Identity review. |
| Format reference (OQ-02) | API owner | `TransferReferenceGenerator` sinh theo đề xuất `TXN[0-9]{8}[A-Z0-9]{8,20}` (SecureRandom); xác nhận format trước khi khóa, không tự đổi; unique DB là chốt cuối. |
| Chuẩn hóa Unicode NFC | API owner/BA | `02` §6.2 để ngỏ (đề xuất); không tự thêm vào production; chốt trước khi đóng băng hash vector. |
| Policy PII description/key | BA/Security | Chưa có thuật toán duyệt (chung với 03); hash nhận description đã validate hình thức; AC PII giữ Blocked. |
| Replay correlation (OI-CON-13) | API/FE/QA | Body gốc/header hiện tại đã theo DEC-TRF-004; cần review và kiểm chứng end-to-end. |
| TTL config version runtime | Transfer/DBA/Ops | Cột version có trong migration candidate; cơ chế đọc snapshot/config đổi cần review và test trước nghiệm thu. |
| Provider thật + PostgreSQL | Đạt/Vũ/Thịnh | Identity challenge, Limit guard/reservation, Account resolve, Audit C1 chưa có runtime; chặn AC race/rollback/integration; fake chỉ test/dev. |
| Kích hoạt boundary | Backend Lead/API | DEC-TRF-016: chỉ đăng ký HTTP khi 04/05 + provider thật + migration đã review; không kích hoạt money path sớm. |

**Điều kiện đóng task:** mọi AC trong [backlog](README.md) được Verified với provider thật (Identity/Limit/Account/Audit) và PostgreSQL nhiều connection; hash vector chốt và dùng chung provider/fake; DEC-TRF-008 có review liên module và evidence race 428/key/challenge/midnight; replay/428 end-to-end đạt cùng 03/05; gap UUID/BIGINT và OQ-02 được xử lý hoặc ghi blocker release có owner; evidence tái lập theo `09` §10 và reviewer liên quan xác nhận. Không đánh dấu `[x]` chỉ vì tài liệu đã viết hay fake đã pass; vấn đề chưa quyết định ghi **Blocked**, không xóa tiêu chí để đóng task.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 16/11/2026 | Chi tiết phạm vi, baseline hash/idempotency/TTL, step-up T0, T1 insert-first, dependency, test oracle và điều kiện đóng BE-TRF-04; ghi hiện trạng primitive/preflight/T1 candidate và giới hạn fake; chưa triển khai thêm hoặc nghiệm thu. |
| v1.1 | 17/11/2026 | Qoder: triển khai seam `TransferCreateOrchestrator`, đóng gap boundary challenge UUID trong candidate T1, port T0 `StepUpChallengePort`, `TransferReferenceGenerator`, seam `Settlement` receipt và test fail-closed TTL (DEC-TRF-017); 28 test mới, check 366 backend/163 Transfer xanh. AC vẫn Blocked chờ provider thật/PostgreSQL/review; không đánh dấu Verified. |