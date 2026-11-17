# BE-TRF-04 — Idempotency, canonical hash, T1 và step-up

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-04`
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** Blocked — primitive và candidate preflight/T1 đã có test fake; chưa nối runtime, chưa có provider thật, chưa chứng minh transaction/challenge/replay liên module.
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, Identity, Limit, Account, Audit, Common, Architect, API, QA, Security, FE và Ops theo phạm vi.

## 1. Mục tiêu và phạm vi

Chuẩn hóa payload và hash theo `07` §5, tra cứu idempotency trước preflight, đánh giá step-up dưới guard theo customer, điều phối challenge T0 và tạo intent bền vững trong T1. Task này là ranh giới giữa boundary HTTP BE-TRF-03 và money transaction BE-TRF-05: T1 chỉ tạo `transfer PENDING`, `idempotency_record RECEIVED`, reservation và audit `TRANSFER_CREATED`; không debit/credit, không post ledger, không gửi notification.

Ngoài phạm vi: controller/DTO/validation HTTP và query receipt (BE-TRF-03), T2 lock/balance/ledger/limit/fraud/audit completion/outbox (BE-TRF-05), recovery/reconciliation/purge (BE-TRF-06), telemetry/notification wiring (BE-TRF-07). T1 không giữ transaction qua OTP, không gọi HTTP/broker, không bọc T0–T2 trong một outer transaction và không trả `201` trước khi T2 commit.

**Hiện trạng 16/11/2026:** các primitive `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy`, `IdempotencyRetention` đã có nhưng chưa nối runtime. `TransferPreflightCandidate` có 11 test fake cho lookup trước preflight, đọc lại khi từ chối, winner/conflict/replay, thứ tự và binding T1; candidate tự hash payload immutable và kiểm tra actor/customer/key/hash/source/destination đã resolve/amount/currency/description trước T1. `TransferIntentCandidate` có 10 test fake cho guard, recheck key, challenge, rollback giả lập và đọc record thắng sau rollback. Chưa có provider adapter thật, authorization/PII/security hoàn chỉnh, HTTP replay, proof transaction PostgreSQL hay tích hợp guard/challenge/reservation liên module. Aggregate gần nhất sau BE-TRF-03 là **338 test backend, 135 test Transfer**, 0 fail/error/skip; đây là snapshot candidate, không phải bằng chứng provider hay end-to-end. Xem [evidence BE-TRF-04/05](evidence/BE-TRF-04-05-candidate.md) và [evidence BE-TRF-03](evidence/BE-TRF-03-candidate-verification.md).

## 2. Nguồn và dependency

- [[digitalbanking-docs/docs-common/07. API contracts.md]] §5.1–§5.3 (key global, canonical hash, replay/conflict/in-progress), §6 (step-up và challenge), §4.3 (catalog lỗi), §8.3.1 (mapping `POST /transfers`); [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]: hợp đồng máy đọc cần đối chiếu.
- [[digitalbanking-docs/docs-common/03. SRS.md]] §2.13, AC-010–AC-013, AC-033–AC-034; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.1/§4.4.3: `transfer`, `idempotency_record`, `auth_challenge` và lifecycle.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§8, §12: T0/T1/T2, insert-first, unique race, challenge consume, crash window; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §5 (Identity), §8.1–§8.2 (idempotency/T1), §10.1 (guard/reservation), §11.1 (audit), §13.4 (baseline Transfer v1).
- [[digitalbanking-docs/docs-common/design/04. security.md]]: challenge binding, single-use, no raw key/body/PII; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6–§5.7: audit C1/C3 và trường hợp không ghi audit.
- [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §6, §11: orchestration, insert-first (BE-CON-31/32), SQLSTATE/idempotency mapping (BE-CON-54), field error (BE-CON-55); [Thiết kế Transfer](../design.md) §2, §5, §5.1; [backlog](README.md) §2; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-001/002/004/005/008/011/012/013/015/016.
- [[digitalbanking-docs/docs-common/09. Test plan.md]] §7.3 (`T-IDEM`), §7.4/§7.4.1 (`T-REC`, step-up), §7.7 (`CT-API`), §10: evidence. `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend; `07` quyết định ngữ nghĩa API.

| Dependency | Cần cho BE-TRF-04 | Trạng thái / cách xử lý |
|---|---|---|
| BE-TRF-01 | Baseline key global, replay/conflict/in-progress, step-up protocol và oracle | Baseline `05` §13.4 dùng được cho phát triển độc lập; review liên module và evidence integration vẫn Blocked. |
| BE-TRF-02 | `transfer`, `idempotency_record`, `auth_challenge` FK/unique/TTL/version; persistence T1 | Migration/JPA candidate ngoài Flyway production; T1 transaction thật cần migration provider và review DBA. |
| BE-TRF-03 | Boundary HTTP, parser, session/permission, mapping outcome/Problem | Boundary candidate có; orchestrator 04/05 chưa đăng ký, endpoint production chưa kích hoạt. |
| Identity — Đạt | Session, permission; challenge bind user/customer/purpose/hash/key/expiry; verify/consume một lần; OTP dispatch ACK | SPI/common có, implementation và gateway chưa có; fake chỉ test/dev. Điều kiện nghiệm thu là challenge/OTP thật. |
| Limit — Vũ | Guard customer, reservation PENDING, settlement/release trong T2 | Contract thiết kế tại `05` §10.1; provider/transaction thật chưa có. Race step-up và reservation phải chứng minh trên PostgreSQL. |
| Account — Đạt | `resolveDestination`, `assertOwnership`, trạng thái nguồn/đích (read-only preflight) | `account.api` chưa có implementation; fake test-only theo `05` §13.4; IDOR/ownership thật là điều kiện nghiệm thu. |
| Audit — Thịnh | `TRANSFER_CREATED` C1 fail-closed; `SECURITY_IDEMPOTENCY_CONFLICT` C3 best-effort | Contract `recordInTransaction`/`recordStandalone` có; implementation thật và transaction proof chưa có. |
| Common — Phương | JCS, SHA-256, HMAC fingerprint, Clock, request context | Primitive có code; integration với Identity/Transfer và test vector chưa được chứng minh. |
| API/FE/QA | Replay status/body, correlation body/header, retry/challenge client | Cần review `07`/YAML, CI contract diff và kiểm chứng replay 201/422. |
| Ops/Security | TTL/purge, key entropy/DoS, log/PII, runbook PENDING | TTL/purge thuộc 06; key global residual risk và log hygiene cần Security review. |

Giao tiếp mới chưa có trong `05`: nêu phương án qua public API/DTO của owner, ghi đề xuất vào `05` để review rồi test fake theo hợp đồng đó; không truy cập repository/entity nội bộ module khác, không tạo bean fake production.

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / điều kiện nghiệm thu |
|---|---|---|
| TRF-04.1 | Chốt canonical payload và hash. | Normalize đúng `07` §5.2/`05` §8.2: amount 2 decimals bỏ leading zeros, currency uppercase, description trim/null→rỗng, destination theo type/value; JCS RFC 8785; `request_hash = lowercase_hex(SHA-256(UTF-8(JCS(payload))))`. Không đưa header, timestamp, correlation ID, device info hoặc resolved destination ID vào hash. Có test vector scale/null/empty/field order/destination type; không log raw key/body/PII. |
| TRF-04.2 | Preflight idempotency đúng thứ tự. | Lookup key global **trước** account reads; terminal cùng actor/customer/hash replay status/body gốc; khác actor/customer/hash conflict an toàn; nonterminal `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`. Khi preflight từ chối nghiệp vụ, đọc lại key trên primary: record đã xuất hiện thì xử lý idempotency, vẫn vắng thì trả lỗi không tạo intent. DB error fail-closed. |
| TRF-04.3 | Step-up policy và guard. | Ngưỡng mặc định: một lệnh `≥ 10.000.000 VND` hoặc tổng dự kiến trong ngày `> 20.000.000 VND` (DEC-TRF-002). Dưới guard customer tính completed ngày nghiệp vụ hiện tại + **mọi** reservation PENDING còn hiệu lực kể cả ngày trước + lệnh đang xét, mỗi lệnh một lần; zone `Asia/Ho_Chi_Minh` cấu hình, thời gian primary. Nhánh 428 không tạo transfer/idempotency/ledger/reservation bởi request đó. |
| TRF-04.4 | Challenge T0 và consume T1. | Challenge bind user/customer, purpose, `request_hash`, idempotency key, expiry; chỉ challenge `VERIFIED` chưa hết hạn được consume một lần. 428 chỉ trả sau ACK + persist OTP theo DEC-TRF-011; timeout/ACK không rõ → `503 OTP_DISPATCH_FAILED`. Consume sai binding/expiry → 409 `STEP_UP_CHALLENGE_MISMATCH` hoặc 410 `AUTH_CHALLENGE_EXPIRED`; rollback T1 hoàn lại consume. |
| TRF-04.5 | T1 insert-first và unique race. | T1 transaction riêng: guard → insert `transfer PENDING` → insert key `ON CONFLICT DO NOTHING RETURNING` → consume challenge → reservation → audit `TRANSFER_CREATED` cuối. Request thua unique rollback toàn bộ T1 trước khi đọc lại trong transaction mới; không orphan. Reference collision/challenge unique là lỗi riêng, không nhầm với replay key. |
| TRF-04.6 | Audit và trạng thái sau T1. | Audit C1 fail-closed là thao tác DB cuối; lỗi rollback T1. Commit T1 để lại `PENDING` + `RECEIVED`, không response replay, không ledger; crash sau T1 do recovery worker xử lý. C3 conflict best-effort + fallback log/metric, không đổi response deny và không lộ dữ liệu actor khác. |
| TRF-04.7 | TTL/version tại T1. | Snapshot `TTL_T1` hợp lệ ≥24h và version; `expires_at = created_at + TTL_T1`; config thiếu/sai fail-closed. PENDING không bị purge dù quá expiry; record còn tồn tại vẫn replay/conflict; terminal TTL thuộc 04/05. |
| TRF-04.8 | Kiểm thử, contract và evidence. | Test hash vector, idempotency matrix, step-up race/expiry, T1 rollback/key race/audit failure/crash trên PostgreSQL nhiều connection và provider thật; contract `CT-API-006–011`; security/log/PII; evidence theo `09` §10. Thiếu provider/môi trường ghi **Blocked**, không đánh dấu Verified bằng fake. |

## 4. Idempotency và canonical hash

### 4.1. Canonical payload

Theo `07` §5.2 và `05` §8.2, payload chuẩn hóa tối thiểu gồm:

| Field | Chuẩn hóa |
|---|---|
| `customerId` | ID số/chuỗi canonical từ principal; không nhận từ body. |
| `sourceAccountId` | ID canonical; thuộc customer đã xác thực. |
| `destination` | Object `{type,value}`: `ACCOUNT_ID` hoặc `ACCOUNT_NUMBER`; **không** thay bằng `destinationAccountId` đã resolve. |
| `amount` | Decimal string 2 chữ số thập phân, bỏ leading zeros; không dùng floating point. |
| `currency` | Uppercase; MVP chỉ `VND`. |
| `description` | Trim; `null` và chuỗi rỗng cùng canonicalize thành `""`. |

Thuật toán: serialize object theo **RFC 8785 JCS**, lấy UTF-8 rồi `lowercase_hex(SHA-256(...))`, lưu `CHAR(64)`. Không đưa `Idempotency-Key`, header, timestamp, correlation ID, device info, `reference_no` hay ID tài khoản đích đã resolve vào hash.

Hệ quả cần review với API/FE:

- Cùng một tài khoản đích nhưng client gửi `accountId` khác `accountNumber` tạo payload/hash khác; đây là hành vi baseline theo `type/value`, không tự resolve rồi hash lại.
- Thay đổi canonicalization là breaking với record đang lưu. Chưa có cột `hash_version` cho idempotency; nếu phải đổi thuật toán, cần version/migration và kế hoạch tương thích được review, không tự đổi tại chỗ.
- Test vector phải lấy/đối chiếu `07` §5.2 và giữ cùng vector cho fake/provider; không tự tạo giá trị hash “chuẩn” khi nguồn chưa có.

### 4.2. Decision matrix

| Trạng thái record | Điều kiện | Kết quả |
|---|---|---|
| Không có key | Key vắng trên primary sau lookup | Tiếp tục preflight account/step-up, rồi T1 với key này. |
| Terminal cùng actor/customer/hash | `COMPLETED` hoặc `FAILED`, `request_hash` khớp, `userId` + `customerId` khớp | Replay nguyên status/body gốc (`201` hoặc `422`) + `Idempotent-Replayed: true`; không tạo transfer/debit mới. |
| Nonterminal cùng actor/customer/hash | `RECEIVED`/`IN_PROGRESS` | `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`; HTTP không tự chạy T2; worker nội bộ recovery. |
| Khác hash | Key tồn tại, payload hash khác | `409 IDEMPOTENCY_CONFLICT`; C3; không lộ body/hash/actor gốc. |
| Khác actor | Cùng key/customer nhưng `userId` khác (kể cả hai user cùng customer) | `409 IDEMPOTENCY_CONFLICT`; C3; không replay response của người khác. |
| Key đã purge | Không còn record | Có thể là yêu cầu mới; client nên query reference trước. Không hứa chống trùng vĩnh viễn. |
| DB lỗi khi lookup | Không xác định được trạng thái | Fail-closed 503/500 theo catalog; không đoán key vắng, không tạo intent. |

Key global 1–255 ký tự `^[A-Za-z0-9._:~-]{1,255}$`; client nên dùng UUIDv4/CSPRNG ≥128 bit và giữ nguyên key/payload khi retry (DEC-TRF-012). Format không chứng minh entropy; preclaim/DoS còn là rủi ro residual, không coi rate limit là triệt tiêu.

### 4.3. Thứ tự preflight

1. Boundary đã xác thực session/permission và parse body (BE-TRF-03). T1 không nhận actor/customer từ body.
2. Normalize + hash payload; lookup key global trên primary.
3. Nếu có record: phân loại replay/conflict/in-progress và kết thúc — **không** resolve account, không đánh giá step-up, không tạo gì.
4. Nếu key vắng: resolve destination/ownership/status và đọc balance tham khảo (không lock); preflight từ chối sớm nếu chắc chắn không thể thành công.
5. Đánh giá step-up dưới guard customer (DEC-TRF-008); nếu cần và chưa có challenge hợp lệ → T0 tạo challenge/trả 428, không giữ transaction qua OTP.
6. Khi preflight từ chối, **đọc lại key trên primary**: record đã xuất hiện → xử lý idempotency; vẫn vắng → trả lỗi không tạo intent. DB error fail-closed.
7. Chỉ khi key vẫn vắng và step-up đã thỏa mới vào T1.

Lookup-before-account-read là bắt buộc (CT-API-007, DEC-TRF-004/016): replay phải thắng preflight để không phụ thuộc trạng thái account hiện tại.

### 4.4. TTL và retention tại T1

Theo DEC-TRF-005/013:

- T1 đọc một snapshot `TTL_T1` hợp lệ (≥ `86400` giây) và version, set `expires_at = created_at + TTL_T1`; không dùng giá trị đoán khi config thiếu/sai/không đọc được.
- Ghi `ttl_t1_seconds` và `ttl_t1_config_version` cùng record; version là chuỗi không rỗng tối đa 128 ký tự.
- T1 không set response/expiry terminal. Terminal transaction (05) đọc `TTL_terminal`, ghi response masked và `expires_at = max(expires_at hiện có, completed_at + TTL_terminal)`.
- PENDING không purge kể cả quá TTL. Record còn tồn tại dù quá `expires_at` vẫn replay/conflict; chỉ sau purge key mới có thể thành yêu cầu mới (06).

### 4.5. Replay contract handoff

BE-TRF-04 tạo dữ liệu để BE-TRF-03/05 map HTTP, nhưng không tự dựng Problem hay trả response:

- Chỉ terminal mới có response được replay; không replay 5xx tạm, không replay PENDING.
- Body replay giữ correlation gốc; header `X-Correlation-ID` phản ánh request hiện tại (DEC-TRF-004, OI-CON-13).
- `201` chỉ sau T2 commit; T1 commit chỉ trả outcome nội bộ `PENDING/IN_PROGRESS` cho orchestrator, không false success.
- Lỗi pre-T1 không có `reference`/`transferStatus`; lỗi sau T1 đã commit FAILED dùng `422` + `reference`/`failureCode` theo 05.

## 5. Step-up T0

### 5.1. Policy và guard

Theo DEC-TRF-001/002/008:

| Trigger | Ngưỡng mặc định | Biên |
|---|---|---|
| Một lệnh | `amount ≥ 10.000.000 VND` | `9.999.999,99` không kích hoạt; `10.000.000,00` kích hoạt. |
| Tổng ngày dự kiến | `> 20.000.000 VND` | `20.000.000,00` không kích hoạt; `20.000.000,01` kích hoạt. |

Tổng dự kiến = tổng transfer `COMPLETED` thuộc ngày nghiệp vụ hiện tại + **mọi** reservation `PENDING` còn hiệu lực của customer (kể cả tạo từ ngày trước) + amount lệnh đang xét; mỗi lệnh tính một lần, FAILED không đóng góp. Dùng guard theo customer để hai request đồng thời không cùng thấy tổng thấp rồi bỏ qua step-up. Ngày nghiệp vụ theo `Asia/Ho_Chi_Minh` cấu hình và thời gian primary; lưu ngày/version để truy vết. Step-up không thay hạn mức và không tự chấp thuận lệnh vượt limit.

### 5.2. Challenge lifecycle

- Challenge gắn user/customer, purpose `TRANSFER`, `request_hash`, idempotency key và expiry; không dùng lại cho request khác. `transfer.step_up_challenge_id` nullable + partial unique bảo vệ một challenge cho tối đa một transfer.
- T0 tạo challenge và trả `428 STEP_UP_REQUIRED` kèm `challenge` (`challengeId`, phương thức, `expiresAt`) theo `07` §6.3; response này **không** có `reference`/`transferStatus` và không tạo transfer/idempotency/ledger/reservation bởi request đó.
- OTP dispatch theo DEC-TRF-011: tạo challenge trong DB transaction, gửi OTP sau commit ngoài transaction; chỉ trả 428 sau khi ACK gateway và persist thành công. Timeout/ACK không rõ/không persist được → `503 OTP_DISPATCH_FAILED`, invalidate khi an toàn; resend tối đa 3, cooldown 60 giây, TTL 5 phút, giữ số lần sai; generation CAS ngăn ACK cũ hồi sinh mã cũ.
- Request khác cùng key có thể commit T1 trước khi client nhận 428; retry của client phải phân loại idempotency trước, challenge dư hết hạn/invalidate an toàn.

### 5.3. Race và ánh xạ lỗi

- Hai key khác nhau, mỗi lệnh dưới ngưỡng nhưng tổng vượt ngưỡng: guard tuần tự hóa; request sau thấy reservation của request trước và phải nhận 428; nhánh 428 không tạo intent/reservation.
- Hai request cùng key: guard + recheck key; unique global là chốt cuối. Một winner T1; loser rollback rồi đọc lại, trả replay/in-progress, không double consume challenge.
- Challenge đã `CONSUMED`/`EXPIRED`/sai binding: T1 rollback; `409 STEP_UP_CHALLENGE_MISMATCH` hoặc `410 AUTH_CHALLENGE_EXPIRED`/`AUTH_OTP_EXPIRED` theo catalog.
- Không giữ lock/transaction khi chờ user OTP; không gọi gateway trong T1.

## 6. T1

### 6.1. Ranh giới transaction

T1 là transaction PostgreSQL ngắn, riêng biệt, `READ COMMITTED`; không có outer transaction bao T0–T2. Baseline timeout: `lock_timeout` 2s, `statement_timeout` 5s; deadlock/lock timeout rollback và retry có giới hạn 2–3 lần với backoff + jitter trên **toàn transaction mới**. Không network, broker, OTP hay notification trong T1.

### 6.2. Thứ tự ghi và unique race

Theo `05` §8.1, `design/02` §6 và DEC-TRF-008:

1. Lấy guard customer (cần cho step-up/reservation), đọc lại key dưới guard.
2. Insert `transfer PENDING` trước (FK `idempotency_record.transfer_id NOT NULL`); set reference, customer/actor, source/destination, amount/currency, description, correlation, TTL snapshot và `step_up_challenge_id` nếu có.
3. Insert `idempotency_record` với `INSERT ... ON CONFLICT DO NOTHING RETURNING`; không “select rồi insert”.
4. Consume challenge `VERIFIED` đúng binding/expiry; ghi `CONSUMED` atomically.
5. Tạo reservation PENDING qua Limit API dưới cùng guard (amount, customer/account, business date/version); FAILED sau này release, COMPLETED settle trong T2.
6. Audit `TRANSFER_CREATED` (C1, `recordInTransaction`, MANDATORY) là thao tác DB cuối; commit.

Thứ tự chính xác giữa challenge và reservation phải theo lock order toàn cục `transfer → auth_challenge → account ASC → limit customer/account → audit` và được Limit/Identity review; không giữ lock sau audit. Nếu `ON CONFLICT` không trả row, request thua unique: rollback **toàn bộ** T1 (kể cả transfer vừa insert và reservation), rồi mở transaction mới đọc record thắng trên primary và phân loại replay/conflict/in-progress. Không bắt conflict rồi commit transfer mồ côi. Collision `reference_no` hoặc `step_up_challenge_id` là lỗi unique riêng: sinh reference mới trong giới hạn hoặc fail an toàn, không xử lý như replay key.

### 6.3. Consume challenge và reservation

- Consume dùng điều kiện `challenge_id`, `status='VERIFIED'`, user/customer/purpose/`request_hash`/key/`expires_at > now()`; số dòng cập nhật 0 → rollback T1 và trả mismatch/expired. Partial unique trên transfer bảo vệ thêm tầng DB.
- Rollback T1 hoàn lại consume challenge, reservation và transfer/idempotency cùng nhau; không có trạng thái nửa vời.
- Reservation PENDING không release theo TTL hoặc qua nửa đêm; chỉ release khi FAILED, settle khi COMPLETED trong T2 (DEC-TRF-008/015).
- T2 không cần lock lại challenge theo luồng chuẩn; nếu re-validate binding thì lock challenge ngay sau transfer row và giữ đúng lock order.

### 6.4. Audit, crash và trạng thái sau commit

- Audit `TRANSFER_CREATED` fail-closed: lỗi audit rollback T1, không có PENDING/RECEIVED giả. Không ghi raw key/body/PII vào audit facts.
- Conflict key/actor ghi C3 `SECURITY_IDEMPOTENCY_CONFLICT` best-effort qua `recordStandalone`; lỗi audit C3 fallback security log + metric/alert, không đổi response deny và không lộ response của actor khác.
- T1 commit để lại `transfer PENDING` + `idempotency RECEIVED`; chưa có ledger/usage/response. Nếu T2 chưa chạy hoặc crash sau T1, recovery worker (06) claim theo fence; HTTP retry thấy nonterminal → `409` và không tự resume money path.
- Log/metric dùng fingerprint HMAC hoặc ID nội bộ, không raw key/hash/body; metric đề xuất `transfer_created_total`, `idempotency_*`, `transfer_pending_age_seconds`, `audit_write_failure_total` (wiring thuộc 07).

## 7. Hiện trạng candidate và khoảng trống

| Thành phần | Hiện có | Giới hạn / điều kiện trước activation |
|---|---|---|
| `TransferRequestHash` | Canonical hash/primitive độc lập | Chưa nối runtime; cần test vector `07` §5.2, đối chiếu payload immutable của preflight candidate và review API. |
| `IdempotencyDecision` | Phân loại replay/conflict/in-progress | Chưa có lookup/DB/HTTP; fake không chứng minh unique race hay cross-actor. |
| `StepUpAmountPolicy` | Trigger theo amount (và ngưỡng cấu hình) | Chưa chứng minh tổng ngày + reservation PENDING qua ngày, guard hay config version. |
| `IdempotencyRetention` | Tính TTL/expiry | Chưa có snapshot/version trong DB, config fail-closed hay purge race. |
| `TransferPreflightCandidate` | 11 test fake: lookup trước preflight, reread khi từ chối, winner/conflict/replay, binding T1 | Chưa đăng ký; Account resolution phụ thuộc port tin cậy, caller phải authenticate/authorize; chưa có HTTP replay/security/contract. |
| `TransferIntentCandidate` | 10 test fake: guard/recheck key, challenge, rollback giả lập, đọc record thắng | Rollback chỉ giả lập; chưa có PostgreSQL transaction, reservation/challenge/audit thật. |
| Tích hợp provider | Chưa có | Identity challenge/OTP, Limit guard/reservation, Account read, Audit C1/C3 thật đều Blocked; fake chỉ test/dev. |

Aggregate 338/135 là snapshot local với synthetic data, không phải provider integration, D1–D5 hay release evidence. Candidate package nằm ngoài component-scan; không kích hoạt production khi orchestrator, migration và provider chưa review (DEC-TRF-016).

## 8. Kế hoạch kiểm thử và evidence

| Nhóm / liên kết | Thiết lập và oracle tối thiểu |
|---|---|
| Hash/unit | Vector `07` §5.2: amount scale 0/1/2/>2, leading zeros, `0.00`, null/empty description, field order, Unicode/escape theo vector nguồn, destination `ACCOUNT_ID` vs `ACCOUNT_NUMBER`; cùng input cho cùng hash, khác representation cho hash khác theo baseline. Assert không log raw key/body/PII. |
| Idempotency matrix — `T-IDEM-01–06` | PostgreSQL nhiều connection: same key/hash concurrent → một transfer/debit; khác hash/actor → conflict + C3, không lộ dữ liệu; terminal replay `201`/`422` + header; nonterminal `409` + `Retry-After`; crash trước/sau T1; key cross-customer. |
| Step-up — `T-STEP-01/02`, `CT-API-010` | Hai key dưới ngưỡng nhưng tổng vượt → một 428, không intent/reservation; biên 9.999.999,99/10.000.000,00 và 20.000.000,00/20.000.000,01; challenge binding/expiry/consume một lần; rollback T1 hoàn consume; 428 rồi request khác cùng key commit trước; retry không double consume. |
| Guard/reservation — `T-LIM-01–03` | Reservation PENDING ngày trước vẫn được tính; không double count; T1 rollback release reservation; settlement/release chỉ trong T2; config/zone/version đổi giữa chừng fail-closed hoặc dùng snapshot đã đọc. |
| T1 transaction | Insert transfer rồi key thua unique → rollback toàn bộ, read lại, không orphan; reference collision riêng; audit fail rollback; crash sau T1 để PENDING/RECEIVED; PENDING quá TTL không purge; hai connection/barrier, timeout/deadlock bounded. |
| Contract/security | `CT-API-006–011`, `CT-API-015–017`: thiếu key, replay, conflict, in-progress, 428, 422; status/body/header/`Retry-After`/`Idempotent-Replayed`/correlation; IDOR/cross-customer, PII/log leak, C3 fallback. |
| Integration | Identity challenge/OTP thật, Account resolve/ownership thật, Limit guard/reservation thật, Audit C1/C3 thật, PostgreSQL với migration production; chạy lại contract suite và transaction proof nhiều connection. Fake chỉ test/dev. |

Mỗi evidence theo `09` §10: test ID/phạm vi, revision, lệnh, môi trường, PostgreSQL/migration/seed, output và artifact synthetic không PII/secret, người chạy và reviewer. Test fake/fixture không gọi là end-to-end; thiếu provider/môi trường ghi **Blocked**. |

## 9. Phối hợp, vấn đề mở và điều kiện đóng

| Vấn đề | Đầu mối | Ảnh hưởng / việc cần làm |
|---|---|---|
| Identity challenge/OTP thật | Đạt | Chưa có implementation/gateway; chặn 428, consume một lần, ACK/SENT, resend/expiry và race. Fake chỉ test/dev. |
| Limit guard/reservation | Vũ | Contract `05` §10.1 có; provider/transaction thật chưa có; chặn race step-up, reservation qua ngày và settlement/release. |
| Account resolve/ownership | Đạt | `account.api` chưa có; chặn preflight ownership/status và IDOR thật. |
| Audit C1/C3 | Thịnh | Chưa có implementation/transaction proof; chặn fail-closed T1 và C3 fallback. |
| Key global và DoS/preclaim | Security/Backend | Residual risk theo DEC-TRF-012; giảm bằng rate limit/quota shared và C3, không triệt tiêu; scoped key là P1. |
| PII trong key/description | BA/Security | Chính sách phát hiện chưa duyệt (BE-TRF-03 §5); T1 tuyệt đối không log raw key/body; AC PII giữ Blocked. |
| Format reference (OQ-02) | API owner | Chưa chốt; T1 sinh reference theo format được duyệt, collision xử lý riêng. |
| Replay correlation (OI-CON-13) | API/FE | Body correlation gốc, header hiện tại; cần review và contract test. |
| Canonicalization version | Backend/API/DBA | Chưa có `hash_version` cho idempotency; nếu đổi thuật toán cần version/migration, không đổi tại chỗ. |
| Business date/timezone/config | Limit/BA | Cần chốt zone/config version và clock primary; ảnh hưởng guard/reservation và step-up qua ngày. |
| TTL/version migration | BE-TRF-02/DBA/Ops | Cột/version chưa có trong production; chặn TTL snapshot và purge an toàn (06). |
| Challenge dư/invalidate | Identity/API | Cần policy invalidate challenge không dùng khi retry/idempotency thắng, không làm lộ trạng thái. |
| Provider/QA/Security review | Các owner | Chưa có review/sign-off; không tự đóng AC. |

**Điều kiện đóng task:** mọi AC trong [backlog](README.md) được Verified với Identity challenge/OTP thật, Limit guard/reservation thật, Account read thật, Audit thật và PostgreSQL migration production; hash vector/contract/security review đạt; replay `201`/`422`, `409`, `428`, consume một lần và rollback được chứng minh; TTL/version và correlation được review; evidence tái lập theo `09` §10 và reviewer liên quan xác nhận. Không đánh dấu `[x]` chỉ vì primitive/candidate test fake đã pass; phần chưa quyết định hoặc thiếu provider ghi **Blocked**, không xóa tiêu chí.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 16/11/2026 | Chi tiết canonical hash, idempotency/preflight, step-up/challenge, T1, hiện trạng candidate, test oracle và điều kiện đóng BE-TRF-04; chưa triển khai hoặc nghiệm thu. |
