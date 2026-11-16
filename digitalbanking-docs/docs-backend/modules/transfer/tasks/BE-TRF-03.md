# BE-TRF-03 — API, validation, quyền và tra cứu

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-03`
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** Blocked — có parser candidate; controller, query service, session/account provider thật và kiểm thử contract/security chưa có
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, API owner, Identity, Account/Customer, QA, Security, FE và Ops theo từng phạm vi.

## 1. Mục tiêu và phạm vi

Xây dựng lớp HTTP inbound và read side của module `transfer`: controller/DTO cho `POST /api/v1/transfers` và `GET /api/v1/transfers/{reference}`, validation biên, lấy actor từ session, authorization ở route và service, mapping Problem/replay, cùng query service trả biên nhận đã mask. Task kết thúc ở boundary HTTP; orchestration T0–T2, idempotency hash/T1, step-up consumption, recovery và migration thuộc BE-TRF-04–06.

Trong phạm vi:

- Adapter inbound, request/response DTO immutable, mapping request đã validate thành command của orchestration; không đưa entity/JPA ra API.
- Validation schema/header/body, mã lỗi và HTTP status theo `07`/OpenAPI; các quy tắc biên amount, destination, currency, description và key.
- Session principal, quyền route đã khai báo, kiểm tra ownership/scope ở service, CSRF của cookie auth, masking và không rò dữ liệu qua lỗi/log.
- Query service theo reference: participant hoặc staff đúng permission/scope; fraud flags chỉ staff có quyền; receipt/status đã mask.
- Contract, controller slice, security/IDOR, masking và regression tests; fake provider chỉ trong test/dev.

Ngoài phạm vi: tính hash, lookup/replay idempotency, tạo transfer PENDING, consume challenge, debit/credit/ledger, audit C1/C2, outbox, recovery, purge và thay đổi schema. Controller không mở transaction bao T1/T2, không tự chạy T2 khi gặp bản ghi chưa terminal và không thay provider bằng stub production.

**Hiện trạng 16/11/2026:** `TransferCreateInput` là parser candidate tại `transfer/adapter/inbound` với 8 test đạt cho shape/UTF-8/byte limit/header/validation; chưa có controller, query service hay runtime wiring. `transfer/api` và `transfer/adapter/in` chỉ có `.gitkeep`. Identity/Account/Customer chưa có public API thật (`api` rỗng); `SessionAuthenticator` mới là interface, chưa có implementation. Common đã có security filter chain, Problem/error catalog, correlation/no-store, request-size và rate-limit theo process. Vì vậy AC về session/ownership/contract/integration vẫn **Blocked**, không được đánh dấu Verified bằng test fake. Xem [evidence candidate](evidence/BE-TRF-04-05-candidate.md) cho phạm vi parser.

## 2. Nguồn và thứ tự nguồn

- [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§6, §7.2.10–§7.2.11, §8.3: header, request/response, lỗi, idempotency, step-up, replay, quyền đọc và masking. OpenAPI [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] là hợp đồng máy đọc cần đồng bộ; `07` quyết định ngữ nghĩa.
- [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13: FR-012–026, AC-006–014/018/022–026/033–034; validation, receipt, step-up và audit.
- [[digitalbanking-docs/docs-common/design/04. security.md]] §3, §6–§9: session/cookie, CSRF/CORS, masking, lỗi an toàn và threat model; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§7, §12: T0–T2 và trạng thái trả về.
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3, §5–§7, §12, §13–§14: session/principal, Account ownership, public API boundary và các điểm còn mở; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §4–§6, §11–§13: layer, DTO, Problem, ArchUnit, timeout và idempotency insert-first.
- [Thiết kế Transfer](../design.md) §1.2–§1.4, §2, §4–§7; [backlog](README.md) §2; [[digitalbanking-docs/docs-common/09. Test plan.md]] §7–§10; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-004/008/012.

Theo quy tắc nguồn của module, `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend; YAML cần được đối chiếu và cập nhật qua API owner, không tự sửa contract. Khi các nguồn mâu thuẫn (ví dụ permission seed và fraud read), ghi blocker và chuyển đúng owner phân xử; không tự suy diễn sign-off.

## 3. Dependency và trạng thái

| Dependency | Cần cho BE-TRF-03 | Trạng thái / cách xử lý |
|---|---|---|
| Identity | `SessionAuthenticator` thật trả `userId`, `customerId`, authorities, CSRF snapshot; principal từ session; permission `TRANSFER_CREATE`/`TRANSACTION_READ(_SELF)`; challenge metadata cho 428 | Mới có interface tại common; Identity `api`/`application` rỗng. Test slice dùng authenticator test-only; runtime **Blocked**. |
| Account / Customer | Resolve `accountNumber`, xác minh ownership/status của source và destination, projection số tài khoản đã mask cho receipt | Account/Customer `api` rỗng; không được đọc bảng/private repository của owner. Integration **Blocked**. |
| Audit | Ghi C3 best-effort cho conflict/security event; không thay audit bắt buộc của T1/T2 | API audit chưa được xác nhận cho consumer; mapping C3 và test leak **Blocked**. |
| Common | Problem/`ErrorCode`, correlation, `no-store`, request-size filter, security filter chain, rate limiter, masking/context | Có sẵn; rate limiter là process-local, không chứng minh quota toàn cụm. |
| BE-TRF-01/02 | Baseline contract/quyết định; schema/state và migration candidate | 01 có baseline nhưng review liên module còn Blocked; 02 migration production/FK chưa có. GET read model chỉ dùng cột đã chốt sau 02. |
| FE/QA/Ops | Contract status/header/body, retry guidance, evidence và quota đa instance | Chưa chốt đầy đủ; xem §9. |

Nếu phát hiện giao tiếp chưa có trong `05`, đề xuất operation qua public API/DTO của owner, ghi vào `05` để review rồi test fake theo hợp đồng đó; không truy cập internals hoặc bảng private của module khác.

## 4. Hợp đồng HTTP baseline

### 4.1. `POST /api/v1/transfers`

| Hạng mục | Baseline theo `07`/OpenAPI | Ghi chú triển khai |
|---|---|---|
| Quyền | `CUSTOMER` + authority `TRANSFER_CREATE`; customer/actor lấy từ session | Route đã khai báo `hasAuthority("TRANSFER_CREATE")`; service kiểm tra `customerId` non-null và ownership source. Không nhận `customerId`/`actor` từ body (unknown field bị từ chối). |
| Header bắt buộc | `Idempotency-Key` đúng một giá trị, 1–255 ký tự `[A-Za-z0-9._:~-]`, không PII | Không log raw key/hash; duplicate/missing key là `400 VALIDATION_ERROR` với `errors[].field = "Idempotency-Key"`. `X-Correlation-ID` tùy chọn, pattern `[A-Za-z0-9._:-]{1,64}`. |
| `Content-Type` | `application/json` | Sai kiểu trả `415 UNSUPPORTED_MEDIA_TYPE`; body malformed `400 MALFORMED_REQUEST`; vượt giới hạn `413 REQUEST_TOO_LARGE`. |
| Body | `TransferCreateRequest` (`07` §7.2.10): `sourceAccountId`, đúng một `destination.accountId` hoặc `destination.accountNumber`, `amount` decimal string, `currency` mặc định `VND`, `description` tùy chọn | Field/kiểu/biên theo §5; DTO và parser không xuất entity. |
| Thành công | `201` + `Location` + `TransferResponse` (`07` §7.2.11) ở trạng thái `COMPLETED` | Chỉ outcome đã commit `COMPLETED` mới trả 201; không báo thành công khi còn `PENDING`/commit chưa rõ. |
| Replay terminal | Giữ nguyên status/body gốc (`201` hoặc `422`) + `Idempotent-Replayed: true` | Body dùng bản đã mask đã lưu; body giữ correlation gốc, header correlation phản ánh request hiện tại (DEC-TRF-004/OI-CON-13). |
| Nghiệp vụ FAILED | `422` Problem có `reference`, `transferStatus`, `failureCode` | Chỉ cho FAILED đã commit sau T1; lỗi trước T1 không được chứa reference/transferStatus. |
| Step-up | `428 STEP_UP_REQUIRED` + metadata challenge theo `07` §6 | 428 không tạo transfer/idempotency/ledger bởi request đó; không trả `Location`. Retry dùng `X-Step-Up-Challenge` và cùng key/payload. |
| Idempotency | Khác hash/actor/customer → `409 IDEMPOTENCY_CONFLICT`; cùng key/hash chưa terminal → `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After` | HTTP không tự resume T2; worker nội bộ phục hồi. Không lộ dữ liệu của actor khác. |
| Lỗi khác | `400/401/403/404/409/413/415/422/429/500/503` theo catalog `07` §4 | RFC 9457 Problem, `application/problem+json`; không SQL/stack/provider detail/PII. |

Body mẫu synthetic (không dùng dữ liệu thật):

```json
{"sourceAccountId":"3001","destination":{"accountNumber":"0123456789"},"amount":"150000.00","currency":"VND","description":"Chuyen tien"}
```

### 4.2. `GET /api/v1/transfers/{reference}`

| Hạng mục | Baseline | Ghi chú |
|---|---|---|
| Quyền route | `TRANSACTION_READ_SELF` hoặc `TRANSACTION_READ` | Đã khai báo trong security filter chain; **không đủ** để quyết định scope. |
| Authorization service | CUSTOMER phải là participant (chủ source hoặc destination) và có `TRANSACTION_READ_SELF`; staff cần `TRANSACTION_READ` + scope server-side | Ownership/participant xác minh qua Account public API; không suy từ role, không đọc bảng Account. |
| Kết quả | `200` + `TransferResponse` đã mask | Trạng thái, reference, amount/currency, account đã mask, timestamps, correlation; `fraudFlags` chỉ staff có `FRAUD_READ`/`FRAUD_REVIEW`. |
| Không tồn tại / ngoài scope | `404 TRANSFER_NOT_FOUND` / `403 AUTH_FORBIDDEN` theo `07` | Giữ đúng semantics 403 khi tài nguyên tồn tại nhưng ngoài scope, 404 khi không tồn tại; không thêm chi tiết làm lộ dữ liệu. |
| Cache/header | `Cache-Control: no-store`, `Pragma: no-cache`, `X-Correlation-ID` | Common `ApiSecurityHeadersFilter` đã set toàn cục; test phải assert trên cả success/error. |
| Đọc | Read-only, không lock/T2, không tra idempotency cho GET | Projection/DTO từ `transfer` (và provider public API); không trả entity/JPA. |

Tên field chính xác của `TransferResponse` và metadata 428 lấy từ `07`/OpenAPI; đối chiếu YAML là đầu việc bắt buộc trước contract test. Không tự thêm envelope `data/meta` hoặc field ngoài schema (BE-API-04/05).

### 4.3. Bảng lỗi và mapping

| Nguồn | Điều kiện | HTTP/code |
|---|---|---|
| Parser | JSON malformed/UTF-8 sai/duplicate key/trailing token | `400 MALFORMED_REQUEST` |
| Parser | Shape/schema/field/header không hợp lệ | `400 VALIDATION_ERROR` + `errors[]` |
| Parser/body filter | Vượt `maxRequestBodyBytes` (mặc định 65.536) | `413 REQUEST_TOO_LARGE` |
| MVC/Security | Content-Type sai / method sai / session thiếu-hết hạn / thiếu quyền | `415` / `405` / `401 AUTH_SESSION_EXPIRED` / `403 AUTH_FORBIDDEN` |
| Preflight | Account đích không tồn tại / inactive / không sở hữu source / thiếu balance / vượt limit | `404 ACCOUNT_NOT_FOUND` / `409 ACCOUNT_INACTIVE` / `403` (cần API/Security chốt) / `422 INSUFFICIENT_BALANCE` / `422 LIMIT_EXCEEDED` |
| Idempotency | Conflict / in-progress | `409 IDEMPOTENCY_CONFLICT` / `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After` |
| Step-up | Cần xác thực nâng cao | `428 STEP_UP_REQUIRED` + metadata |
| Settlement | Business FAILED / fraud block | `422` + `reference`/`transferStatus`/`failureCode` |
| Hệ thống | Audit fail / không xác định / quá tải | `500 AUDIT_WRITE_FAILED` / `500 INTERNAL_ERROR` / `503 SERVICE_UNAVAILABLE` |

`TransferCreateInput.InvalidRequestException` hiện là `IllegalArgumentException`; `GlobalExceptionHandler` không map riêng nên nếu controller không bắt và chuyển `Kind` sẽ rơi vào `500`. Controller phải map tường minh `MALFORMED/VALIDATION/TOO_LARGE` sang catalog, không để exception của parser lọt ra ngoài.

## 5. Validation biên

### 5.1. Quy tắc đã có trong parser candidate

| Nhóm | Quy tắc hiện có |
|---|---|
| Header | Đúng một `Idempotency-Key`; regex `[A-Za-z0-9._:~-]{1,255}`; không có actor/customer từ client |
| Body | Object JSON, strict UTF-8, duplicate-key detection, không trailing token, chỉ 5 field cho phép |
| Source | Chuỗi ID `[1-9][0-9]{0,18}`; parse `long` |
| Destination | Object đúng một key: `accountId` (ID hợp lệ) hoặc `accountNumber` (`[0-9]{6,20}`); source ≠ destination khi cùng dạng `accountId` |
| Amount | Chuỗi `(0|[1-9][0-9]{0,16})\.[0-9]{2}`; `BigDecimal`; > 0; scale ≤ 2; ≤ `99999999999999999.99` |
| Currency | Chỉ `VND`, mặc định `VND` khi vắng |
| Description | Null/vắng → `""`; ≤ 255 code point; từ chối ISO control và surrogate không hợp lệ; trim |

### 5.2. Phần còn thiếu và phải bổ sung

- **PII policy:** parser không thực thi chính sách cấm PII trong `description`/key. Không tự định nghĩa detector; Security/BA phải duyệt rule set (loại dữ liệu, cách báo lỗi) rồi mới triển khai và test. Key PII không được log/echo.
- **Amount biên:** test thêm `0.00`, `0.01`, max, 3 chữ số thập phân, dấu âm, leading zero, ký hiệu khoa học, dấu phẩy, khoảng trắng, số rất lớn; `-0.00`; không dùng `double`.
- **Destination:** cả hai key, không key nào, object rỗng, `accountNumber` 5/21 ký tự, ký tự không phải số; sau resolve phải kiểm tra ID nguồn ≠ ID đích (kể cả khi request dùng `accountNumber`).
- **Description:** đúng 255/256 code point, emoji/non-BMP, tab/newline/control, surrogate lẻ, chuỗi chỉ khoảng trắng; trim theo codepoint, không cắt mất ký tự.
- **Header/body:** duplicate header, header rỗng/quá dài, body rỗng/`null`/array, unknown field, duplicate JSON key, UTF-8 sai, vượt giới hạn đúng 1 byte; `Content-Type` có charset.
- **Idempotency key:** không kiểm tra entropy; chỉ format. Key dễ đoán là rủi ro client (DEC-TRF-012); server không log/preclaim như bằng chứng bảo mật.
- **Metadata:** `X-Correlation-ID` do common xử lý; controller đọc `RequestContext`, không tự sinh lại hoặc echo raw header không hợp lệ.

## 6. Authorization, session/CSRF, masking và quota

### 6.1. Actor

- Actor và `customerId` chỉ lấy từ `SessionAuthenticator.Session` đã xác thực; không nhận từ body/header.
- Customer phải có `customerId`; nếu thiếu, `POST /transfers` trả `403` (rate-limit filter hiện cũng fail-closed tại đây).
- Session là stateless theo filter chain; controller không giữ hoặc trả session/CSRF token.

### 6.2. POST

- Route yêu cầu `TRANSFER_CREATE`; service kiểm tra thêm role CUSTOMER/authority và ownership tài khoản nguồn qua Account API.
- Không cho customer tạo transfer từ tài khoản không sở hữu hoặc giả mạo `customerId`.
- Idempotency conflict/actor mismatch xử lý theo `04`; controller chỉ map outcome, không tự query key.

### 6.3. GET — ma trận quyền

| Actor | Permission | Điều kiện bổ sung | `fraudFlags` |
|---|---|---|---|
| CUSTOMER | `TRANSACTION_READ_SELF` | Là chủ source hoặc destination, xác minh qua Account API | Không trả |
| OPERATIONS | `TRANSACTION_READ` | Scope server-side do Identity/Admin cấp | Chỉ khi có `FRAUD_REVIEW`/`FRAUD_READ` |
| ADMIN | `TRANSACTION_READ` | Scope server-side | Chỉ khi có `FRAUD_READ`/`FRAUD_REVIEW` |
| AUDITOR | `TRANSACTION_READ` | Scope server-side | Theo seed hiện tại không có `FRAUD_*`; DEC-TRF-012 yêu cầu cấp `FRAUD_READ` — **cần đồng bộ permission seed/`07`** |

Kiểm tra ở service, không chỉ role/route. Không dùng 403/404 như biện pháp chống enumeration; reference khó đoán, rate limit và security audit là giảm thiểu, không triệt tiêu.

### 6.4. Session và CSRF

- Cookie auth (`DB_SESSION`) bắt buộc `X-CSRF-Token`; Bearer auth không áp CSRF theo matcher hiện tại. Không hạ cấp hoặc fallback từ Bearer lỗi sang cookie.
- Filter từ chối credential hỗn hợp/trùng; controller không cần và không được nới lỏng các kiểm tra này.
- 401/403 phải do security chain/Problem writer trả, không tự viết body khác chuẩn.

### 6.5. Masking và lỗi an toàn

- Không trả số tài khoản đầy đủ; mask theo `07`/`04` (tối thiểu giữ hậu tố nhận diện, không lộ toàn bộ). Tên field/định dạng mask phải khớp OpenAPI.
- Không trả `fraudFlags` cho customer; không trả failure detail nội bộ, SQL, constraint, stack, provider message hoặc hash/key.
- Problem `detail` tiếng Việt an toàn, `title` tiếng Anh, `instance` không chứa query PII; `field` dạng JSON path (`amount`, `destination.accountNumber`, `Idempotency-Key`).
- Log/audit/telemetry chỉ correlation; không body, key, OTP, cookie, số tài khoản đầy đủ. Replay body lưu DB phải đã mask.

### 6.6. Rate limit và quota

- `POST /transfers` hiện giới hạn 30 request/phút/customer bằng limiter process-local; vượt trả `429 RATE_LIMITED` + `Retry-After`, capacity lỗi trả `503 SERVICE_UNAVAILABLE` (fail-closed).
- Limiter trong process **không** chứng minh quota toàn cụm khi nhiều instance; cần limiter atomic shared/gateway tin cậy và test trước vận hành đa instance (DEC-TRF-012). Không dùng test limiter local làm bằng chứng quota.

## 7. Ranh giới controller, orchestration và query service

- `TransferController` (`transfer/adapter/in`): parse/validate HTTP, lấy principal/context, gọi application service, map outcome → HTTP. Không `@Transactional`, không tự chạy T1/T2, không truy cập repository/provider internals.
- Application seam đề xuất `TransferOrchestrator.create(...)` trả các outcome đã có trong candidate (`Conflict`, `InProgress`, `Replay`, `PreflightDenied`, `ChallengeRequired`, `Settled`). Controller map đúng một-một, không tự suy trạng thái từ balance/ledger.
- Nếu orchestration trả outcome chưa xác định/PENDING sau T2, không map thành `201`; trả lỗi retryable theo `07`/quyết định API và để worker/recovery xử lý. Mapping chính xác cho nhánh này cần API owner review.
- `TransferQueryService`: read-only, nhận reference + actor, kiểm tra participant/scope, trả projection đã mask. Không dùng idempotency record để trả receipt của người khác; không join bảng private của provider.
- DTO immutable; amount/ID là chuỗi trong JSON; thời gian ISO 8601 UTC; không success envelope ngoài schema; lỗi dùng Problem.
- `Location` trỏ tới `GET /api/v1/transfers/{reference}`; không đưa reference vào lỗi trước T1.

## 8. Kế hoạch kiểm thử và evidence

| Nhóm | Thiết lập và oracle tối thiểu |
|---|---|
| Parser unit | Mở rộng 8 test hiện có theo §5.2: biên amount, destination, description, header, body size, duplicate/unknown field, UTF-8; assert error kind/field an toàn và không echo giá trị. |
| Controller slice | Dùng Spring MVC + security filter chain thật với `SessionAuthenticator` test-only; assert 201/Location/body, 200 GET, 400/401/403/404/409/413/415/422/428/429; `X-Correlation-ID`, `no-store`, `Pragma`; không có success envelope. |
| Contract | Validate request/response/status/header với OpenAPI và `07 §7.2.10–11/§8.3`; vector replay 201/422, header `Idempotent-Replayed`, body correlation gốc; phát hiện lệch schema trước khi merge. |
| CSRF/session | Cookie auth thiếu/sai CSRF bị từ chối; Bearer không yêu cầu CSRF; credential hỗn hợp/trùng bị từ chối; session hết hạn/không quyền trả Problem chuẩn; controller không nhận actor từ body. |
| Authorization/IDOR | Customer A đọc transfer của B → 403; customer không participant → 403; reference không tồn tại → 404; customer là destination participant được đọc; staff thiếu scope → 403; `fraudFlags` ẩn với customer và staff thiếu quyền. |
| Masking/leak | Quét response, Problem, replay body, log/audit: không full account number, key/hash, OTP, cookie, credential, provider detail; `errors[]` không echo giá trị bị từ chối. |
| Quota | 429 + `Retry-After`; capacity 503; ghi rõ limiter process-local, không tuyên bố quota đa instance. |
| Architecture | ArchUnit: controller/DTO không import entity/JPA/provider internals; controller không `@Transactional`; DTO không expose entity; chỉ dùng provider `api`. Có fixture âm chứng minh rule bắt vi phạm, không coi suite rỗng là bằng chứng. |
| Integration | Sau khi có Identity/Account thật: session thật, resolve/ownership source-destination, provider error mapping, contract suite và security review. Thiếu provider ghi **Blocked**, không thay bằng stub production. |

Chạy `./gradlew check`; test PostgreSQL/lock/transaction không thuộc phạm vi task này nhưng controller contract phải chạy lại sau khi T1/T2 được nối. Evidence theo `09 §10`: test ID, revision, lệnh, môi trường, output, artifact synthetic không chứa PII/secret, người chạy và reviewer. Mock/fake chỉ chứng minh mapping/nhánh, không chứng minh session/ownership thật.

## 9. Điều kiện đóng task và điểm cần chốt

Task chỉ chuyển `[x]` khi toàn bộ AC trong [backlog](README.md) được Verified, contract/security tests chạy với provider thật và evidence tái lập được. Các điểm sau cần owner quyết định/chốt trước khi hoàn tất:

| Điểm cần chốt | Owner | Ảnh hưởng |
|---|---|---|
| Rule PII cho `description` và `Idempotency-Key` (pattern, field lỗi, log) | Security/BA | Validation và test leak; hiện parser chưa thực thi. |
| Tên/kiểu field chính xác của `TransferResponse`, metadata `428`, `failureCode` extension; đồng bộ YAML | API owner | Contract test, FE và replay body. |
| Mapping `SOURCE_NOT_OWNED`/Account inactive thành 403 hay 404; giữ semantics 403/404 của `07` | API/Security | IDOR/enumeration và trải nghiệm lỗi. |
| Định dạng masking account trong receipt và nguồn dữ liệu mask (Account API) | API/Account/BA | Response schema và test masking. |
| Quyền `FRAUD_READ` của AUDITOR: seed hiện tại thiếu so với DEC-TRF-012 | Identity/Security/API | GET fraud flags và permission matrix. |
| Quota đa instance (shared atomic limiter) và owner vận hành | Ops/Architect/Security | 429/503, chống abuse khi scale. |
| Outcome/PENDING sau T2 map thành status nào (retryable error) | API/Backend | Không báo false success; client retry. |

Các mục trên là open item được ghi nhận, không phải quyết định đã duyệt. Khi owner chốt, cập nhật `DECISIONS.md` nếu là quyết định dự án, đồng bộ `07`/OpenAPI và ghi link vào task/evidence; không tự sửa nguồn có thẩm quyền.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 16/11/2026 | Tạo chi tiết BE-TRF-03: hợp đồng POST/GET, validation, authorization/CSRF, masking, ranh giới controller/query, test plan và blocker hiện trạng. |
