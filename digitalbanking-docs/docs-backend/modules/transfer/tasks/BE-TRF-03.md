# BE-TRF-03 — API, validation, quyền và tra cứu

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-03`
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** In progress / Blocked — boundary HTTP candidate (controller/DTO/query service/authorization/C3) đã triển khai và test bằng fake; chưa kích hoạt production. Identity/Account/Audit thật, orchestrator BE-TRF-04/05, policy PII, scope staff, quota shared đa instance và review liên module chưa hoàn tất.
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, API owner, Identity, Account/Customer, Common, FE, QA, Security và Ops theo phạm vi.

## 1. Mục tiêu và phạm vi

Xây boundary HTTP cho module Transfer theo `07`: controller mỏng và DTO record cho `POST /api/v1/transfers` và `GET /api/v1/transfers/{reference}`, validation đúng biên, tích hợp session/CSRF và phân quyền tại cửa ngõ lẫn service, query service trả biên nhận đã mask, và mapping lỗi RFC 9457 qua hạ tầng common. Task này chuẩn bị ranh giới adapter ↔ orchestrator để BE-TRF-04/05 ghép outcome vào HTTP mà không đổi contract.

Ngoài phạm vi: canonical hash/replay/idempotency runtime và T1 (BE-TRF-04), T2 money/ledger/limit/fraud/audit/outbox (BE-TRF-05), recovery/purge (BE-TRF-06), telemetry/notification wiring (BE-TRF-07). Adapter không giữ transaction, không tự chạy T2 khi HTTP retry, không tự dựng Problem ngoài `GlobalExceptionHandler`, không nhận actor/customer từ body. Chuẩn bị test consumer bằng fake theo `05` §13.4 khi provider chưa có; fake không thay session thật, account API thật hay PostgreSQL để nghiệm thu.

**Hiện trạng 16/11/2026 (sau BE-TRF-03 candidate):** boundary HTTP đã triển khai theo DEC-TRF-016 và test bằng fake, **chưa kích hoạt production**. `TransferController` + `TransferHttpConfiguration` đặt tại `com.simulatedbank.transfercandidate.web` (ngoài scan root `banking`, không component-scan; đăng ký tường minh khi orchestrator 04/05, provider thật và migration đã review sẵn sàng — theo quy ước candidate `TransferPersistence`). Controller mỏng nhận raw body + header `Idempotency-Key`, ủy quyền parser `TransferCreateInput` (đã nối endpoint, mang `errors[].field` an toàn, 10 test), gọi một application service và map outcome; không `@Transactional`, không entity, không tự dựng Problem. DTO record `TransferResponse`/`TransferDetail` (`@JsonUnwrapped`)/`AccountRef`/`FraudFlagSummary` khớp `07` §7.2.11 và YAML (contract test đối chiếu schema). `TransferCreateService` re-check `TRANSFER_CREATE` + session customer, phát C3 `SECURITY_ACCESS_DENIED`/`SECURITY_IDEMPOTENCY_CONFLICT` qua `SecurityDenyAudit` (best-effort `AuditEventPort` + fallback security log/metric), rồi ủy quyền seam `TransferOrchestrator` (04/05) — lookup idempotency trước account reads để replay thắng preflight (CT-API-007). `TransferQueryService` read-only trả `TransferDetailView` đã mask, ownership nguồn **hoặc** đích qua `AccountOwnershipPort` (TRFC-AC-02), staff `TRANSACTION_READ` theo matrix `07` §3.5 (scope server-side còn Blocked), `fraudFlags` chỉ `FRAUD_READ`/`FRAUD_REVIEW`; 403 tồn tại ngoài scope / 404 `TRANSFER_NOT_FOUND`. Port consumer (`AccountOwnershipPort`, `AuditEventPort`, `TransferReadPort`, `FraudFlagReadPort`, `TransferOrchestrator`) thuộc `transfer.application`, ánh xạ `05` §13.4; không tạo class trong `provider.api` module khác, fake chỉ trong test. Common: regex `GlobalExceptionHandler.fieldError` cho phép gạch nối để giữ field `Idempotency-Key` (BE-CON-55). `./gradlew check` đạt **338 test, 0 fail/error/skip**; 38 test BE-TRF-03 mới + 2 test parser bổ sung. **Chưa đạt/Blocked:** policy cấm PII description/key (chưa thêm regex production); scope staff server-side; format reference OQ-02 (validate theo đề xuất `TXN…`); session/Account/Audit thật và IDOR/ownership thật; orchestrator 04/05 và end-to-end POST; quota shared atomic đa instance (limiter in-process không là bằng chứng toàn cụm); review API/FE/QA; YAML còn thiếu header `Cache-Control: no-store`/`Pragma: no-cache` và default `currency` so với `07` (implementation đã theo `07` qua filter common). Xem [evidence BE-TRF-03](evidence/BE-TRF-03-candidate-verification.md).

## 2. Nguồn và dependency

- [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§6, §7.2.6/§7.2.10/§7.2.11, §8.3, §10: quy ước chung, schema, quyền, idempotency/step-up, endpoint và contract test; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]: hợp đồng máy đọc cần đối chiếu.
- [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13 và AC-006–014, AC-018, AC-022–024, AC-033–034; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.1: business rules description/reference.
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §5.1, §7.1, §8.1, §12, §13.4: session SPI, account read, use case Transfer, common và baseline v1; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6–§5.7: PII allowlist/scanner và sự kiện không ghi audit.
- [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §6.1–§6.7, §7, §11: convention controller/DTO/exception/API và kiểm thử; [Thiết kế Transfer](../design.md) §2, §4; [backlog](README.md) §2; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-003–005, 008, 011, 012, 015.
- [[digitalbanking-docs/docs-common/09. Test plan.md]] §5.2, §7.1/§7.5/§7.7, §10: boundary matrix, test ID và evidence. `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend; `07` là căn cứ ngữ nghĩa API. Mâu thuẫn nguồn chuyển owner/điều hành phân xử, không tự suy diễn sign-off.

| Dependency | Cần cho BE-TRF-03 | Trạng thái / cách xử lý |
|---|---|---|
| BE-TRF-01 | Baseline contract v1 (DEC-TRF-015), oracle và quyết định HTTP/replay/quyền | Baseline `05` §13.4 dùng được cho phát triển độc lập; review liên module và evidence integration vẫn Blocked. |
| BE-TRF-02 | Persistence `transfer`/`idempotency_record` cho GET và lưu response replay | Migration/JPA candidate ngoài Flyway production; GET integration cần migration thật và provider FK. |
| Identity — Đạt | Implementation `SessionAuthenticator` trả `Session(userId, customerId?, authorities, csrfTokenHash)`; expiry/revocation, CSRF | SPI có trong common, implementation chưa có; fake test/dev theo contract, wiring thật + test expiry/revocation/CSRF là điều kiện nghiệm thu. |
| Account/Customer — Đạt | `resolveDestination` (TRFC-AC-01), `assertOwnership` (TRFC-AC-02), masked view; blind index do Account sở hữu | `api` trống; fake cùng vector `05` §13.4; resolve/ownership thật và test IDOR là điều kiện nghiệm thu. |
| Common — Phương | Problem/error catalog, request context/correlation, masking, security filter, body limit | Đã có code; integration với identity thật và transfer chưa được chứng minh, không coi unit test common là bằng chứng business. |
| BE-TRF-04/05 | Outcome orchestrator (`Completed/Failed/StepUpRequired/InProgress/Replay`) để map HTTP | Candidate chưa đăng ký; adapter định nghĩa mapping và test bằng fake outcome; end-to-end POST chỉ đạt khi 04/05 hoàn tất. |
| API/FE/QA | Review OpenAPI/overview, replay correlation, receipt/retry client | YAML đã mô tả 201/422; cần review, CI contract diff và kiểm chứng implementation khớp. |
| Ops/Backend/Common | Quota shared atomic 30 request/phút/customer (DEC-TRF-012) | Limiter in-process hiện tại không chứng minh quota toàn cụm; triển khai shared store và test đa instance trước vận hành. |

Giao tiếp mới chưa có trong `05`: nêu phương án thay thế qua public API/DTO của owner, ghi đề xuất vào `05` để review rồi test fake theo hợp đồng đó; không truy cập repository/entity nội bộ module khác, không tạo bean fake trong production.

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / điều kiện nghiệm thu |
|---|---|---|
| TRF-03.1 | Đối chiếu và đồng bộ contract HTTP. | YAML/overview khớp `07` §8.3 về schema, header, status, error code, replay 201/422 và correlation; CI validate + contract diff chặn lệch; review API/FE/QA ghi nhận. Không tự đổi ngữ nghĩa `07`; sai lệch còn lại có issue/owner. |
| TRF-03.2 | Controller + DTO cho POST. | Controller mỏng theo BE-CON-21–26: record DTO đúng `TransferCreateRequest`, `fail-on-unknown-properties`, không entity, không `@Transactional`, gọi một application service; `201` + `Location: /api/v1/transfers/{reference}` + `TransferResponse` COMPLETED sau commit; map outcome theo §4, Problem qua `GlobalExceptionHandler`; lỗi pre-T1 không chứa `reference`/`transferStatus`. |
| TRF-03.3 | Validation đúng biên. | Amount decimal string `> 0`, scale 2, ≤19 chữ số, không tự làm tròn; đúng một destination (`accountId` hoặc `accountNumber` 6–20 chữ số); source ≠ destination kể cả sau resolve; currency mặc định/chỉ VND; description ≤255 ký tự, trim, từ chối control characters; key 1–255 ký tự đúng pattern; body ≤64 KB; actor/customer lấy từ session. Policy PII theo §5, phần chưa duyệt giữ Blocked. |
| TRF-03.4 | Session/CSRF và authorization POST. | Chỉ CUSTOMER có `TRANSFER_CREATE` và sở hữu nguồn (Account `assertOwnership`) được tạo; kiểm tra lại ở service; session hết hạn/thu hồi → 401, thiếu quyền/ownership/CSRF → 403; cookie session bắt buộc `X-CSRF-Token`, Bearer miễn CSRF; deny ghi C3 best-effort + fallback; không tạo transfer/idempotency/ledger khi deny. |
| TRF-03.5 | GET query/receipt. | `TransferQueryService` read-only trả `TransferDetail` đã mask; CUSTOMER có `TRANSACTION_READ_SELF` và Account xác nhận sở hữu nguồn hoặc đích; staff có `TRANSACTION_READ` và scope server-side; `fraudFlags` chỉ `FRAUD_READ`/`FRAUD_REVIEW`, không lộ customer; resource tồn tại ngoài scope → 403, không tồn tại → 404 `TRANSFER_NOT_FOUND`; `Cache-Control: no-store`; không API update/delete. |
| TRF-03.6 | Bảo vệ response, masking và quota. | `X-Correlation-ID` mọi response kể cả lỗi; replay giữ correlation gốc trong body, header theo request hiện tại (DEC-TRF-004, ngữ nghĩa thuộc 04); `no-store`/`Pragma` và security headers; log/Problem không lộ raw key, body, số tài khoản đầy đủ, PII; quota 30/phút/customer atomic shared, vượt → 429 + `Retry-After`, store lỗi → 503 fail-closed; không dùng limiter local làm bằng chứng toàn cụm. |
| TRF-03.7 | Kiểm thử và evidence. | Test §7 đạt với session thật và account API thật khi nghiệm thu; fake chỉ test/dev; contract suite đối chiếu YAML; ArchUnit consumer-only-api kèm fixture âm; evidence theo `09` §10; thiếu dependency/môi trường ghi Blocked, không đánh dấu Verified bằng mock. |

## 4. Baseline boundary HTTP

### 4.1. `POST /api/v1/transfers` (API-TRF-01)

Quyền `CUSTOMER` + `TRANSFER_CREATE`. Headers: `Idempotency-Key` bắt buộc `^[A-Za-z0-9._:~-]{1,255}$` (thiếu/sai → 400, `errors[].field = "Idempotency-Key"`); `X-Correlation-ID` tùy chọn `^[A-Za-z0-9._:-]{1,64}$` (sai định dạng → 400, không echo); `X-CSRF-Token` bắt buộc với cookie session; `Content-Type: application/json` (sai → 415). Body theo `07` §7.2.10:

| Field | Ràng buộc |
|---|---|
| `sourceAccountId` | ID `^[1-9][0-9]{0,18}$`; thuộc customer trong session; `ACTIVE` kiểm qua Account API. |
| `destination` | Đúng một trong `accountId` (ID) / `accountNumber` (6–20 chữ số); cả hai hoặc không có → 400 `REQUIRED_ONE_OF`; `accountId` phải khác source; resolve number bằng blind index Account-owned, không log số đầy đủ. |
| `amount` | Money `^(0\|[1-9][0-9]{0,16})\.[0-9]{2}$`, `> 0` (`0.00` từ chối); sai scale/precision → 400, server không làm tròn. |
| `currency` | Mặc định `VND`; chỉ `VND`, case-sensitive. |
| `description` | ≤255 ký tự, trim, từ chối control characters; không PII/số tài khoản đầy đủ (policy §5). |

Thành công `201` + `Location` + `TransferResponse` COMPLETED chỉ sau commit có audit bền vững; body bắt buộc `reference`, `status`, `amount`, `currency`, `createdAt`, `correlationId` (SRS §2.13.2); không success envelope chung. `202` async là đề xuất, không thuộc MVP đồng bộ. Mapping outcome/HTTP:

| HTTP | Tình huống | Ghi chú adapter |
|---|---|---|
| 400 / 413 / 415 | Validation, body quá 64 KB, sai media type | Không tạo gì; `errors[].field` JSON path tương đối. |
| 401 / 403 | Session hết hạn/thu hồi; thiếu quyền, sai CSRF, không sở hữu nguồn | `AUTH_SESSION_EXPIRED`/`AUTH_FORBIDDEN`; deny audit C3. |
| 404 `ACCOUNT_NOT_FOUND` / 409 `ACCOUNT_INACTIVE` | Nguồn/đích không tồn tại hoặc không ACTIVE, **phát hiện trước T1** | Không reference/transferStatus; sau T1 đã commit FAILED thì dùng 422 `TRANSFER_FAILED` + `failureCode` tương ứng, không trả 404/409 terminal. |
| 409 `IDEMPOTENCY_CONFLICT` | Cùng key khác hash/actor (kể cả hai user cùng customer) | Không lộ dữ liệu actor khác; C3. |
| 409 `IDEMPOTENCY_IN_PROGRESS` | Cùng actor/key/hash chưa terminal, kể cả stale | `Retry-After`; HTTP không tự chạy T2 (DEC-TRF-004). |
| 422 | `INSUFFICIENT_BALANCE`, `LIMIT_EXCEEDED`, `FRAUD_BLOCKED`, `TRANSFER_FAILED` | Nhánh sau T1 có `reference`/`transferStatus`/`failureCode`; pre-T1 không có. |
| 428 `STEP_UP_REQUIRED` | Cần step-up (DEC-TRF-002/008/011) | Kèm `challenge` theo `07` §6.3; request này không tạo transfer/idempotency/ledger/reservation; chỉ trả sau ACK + SENT đã persist. |
| 201 / 422 replay | Cùng actor/key/hash terminal | Giữ nguyên status/body gốc + `Idempotent-Replayed: true`; body giữ correlation gốc, header correlation hiện tại; semantics thuộc BE-TRF-04. |
| 429 / 500 / 503 | Quota; `AUDIT_WRITE_FAILED`/`INTERNAL_ERROR`; DB/dịch vụ/quota store/OTP dispatch không khả dụng | `Retry-After`/`retryable` theo catalog; không false success, không đoán terminal khi commit chưa rõ. |

### 4.2. `GET /api/v1/transfers/{reference}` (API-TRF-02)

Path `reference` hợp lệ theo kiểu Reference (16–32 ký tự; format chốt theo §5). Success `200` `TransferDetail` = `TransferResponse` + `fraudFlags` optional chỉ staff có `FRAUD_READ`/`FRAUD_REVIEW`; `sourceAccount`/`destinationAccount` là `AccountRef` (`accountId`, `accountNumberMasked` `**** **** <last4>`, `accountNumberLast4`); `failureCode`/`failureMessage` chỉ khi FAILED và thông điệp an toàn; timestamps UTC. Lỗi: 400 (reference sai định dạng), 401, 403 `AUTH_FORBIDDEN` (không tham gia/không đủ quyền/scope), 404 `TRANSFER_NOT_FOUND`, 500, 503. `Cache-Control: no-store`, `Pragma: no-cache`. Read-only; không update/delete.

Chính sách 403/404 theo `07` §3.6 và DEC-TRF-012: resource tồn tại nhưng ngoài scope → 403, không tồn tại → 404. Khác biệt này có thể lộ sự tồn tại; giảm rủi ro bằng reference khó đoán, rate limit và C3, không coi là triệt tiêu enumeration. Customer chỉ xem transfer mình là owner nguồn hoặc đích; kiểm tra ownership qua Account API, không suy từ FK hay `customer_id` lưu sẵn.

### 4.3. Mapping lỗi

Mọi lỗi `application/problem+json` với `type/title/status/detail/instance/code/correlationId/timestamp/retryable` bắt buộc; `errors`/`challenge`/`reference`/`transferStatus`/`failureCode` theo ngữ cảnh `07` §4.1–§4.2. `code` chỉ từ catalog `07` §4.3; `detail` tiếng Việt an toàn, không PII/SQL/stack trace/tên bảng; không trả mã nội bộ. `GlobalExceptionHandler`/`DatabaseErrorMapper` common là nơi duy nhất dựng Problem; SQLSTATE map theo BE-CON-54, không blanket-map unique thành 409, không query/retry trong transaction đã aborted. Validation error trước khi tạo transfer không ghi audit (design/01 §5.7), chỉ technical log/metric.

## 5. Validation và policy PII còn mở

Quy tắc hình thức/độ dài ở §4 đã chốt theo `07` và parser candidate. Riêng policy **cấm PII trong description/key**: `07` §7.2.10, `04` §4.4.1 và SRS FR-025 yêu cầu không chứa PII/số tài khoản đầy đủ nhưng chưa có thuật toán phát hiện được duyệt. Pattern key đã giới hạn charset song vẫn có thể chứa chuỗi số định danh; description là free-text.

- Phương án đề xuất: tái sử dụng pattern scanner của audit (`design/01` §5.6: account number/email/phone/OTP/token) làm baseline phát hiện ở boundary; từ chối 400 khi khớp, không log giá trị khớp. Phạm vi pattern, ngưỡng false-positive và lựa chọn reject/mask phải BA/Security quyết định, ghi `DECISIONS.md` và đồng bộ `07`/`04` trước khi thực thi.
- Chưa quyết định: không tự thêm regex production, không coi parser hiện tại là đã đạt PII; AC tương ứng giữ **Blocked** với owner theo dõi.
- `description` là dữ liệu Level 2 (`design/04`): chỉ trả cho participant/staff được quyền qua GET; không đưa vào audit facts, outbox hay log (allowlist `design/01` §5.6).
- Format `reference_no`: `07` đề xuất `^TXN[0-9]{8}[A-Z0-9]{8,20}$`, `04` §4.4.1 ghi OQ-02 format do API quy định. GET validate theo format đã chốt; sinh reference thuộc T1 (BE-TRF-04). Xác nhận format với API owner trước khi khóa validation, không tự đổi.

## 6. Authorization và data scope

| Actor | Endpoint | Điều kiện | Khi từ chối |
|---|---|---|---|
| CUSTOMER | POST | `TRANSFER_CREATE`; session active + CSRF; Account `assertOwnership(source) = OWNED` | 401/403; C3 `SECURITY_ACCESS_DENIED` best-effort + fallback log/alert; không tạo record. |
| CUSTOMER | GET | `TRANSACTION_READ_SELF`; Account xác nhận sở hữu nguồn **hoặc** đích | Ngoài scope nhưng tồn tại → 403 + C3; không tồn tại → 404. |
| OPERATIONS/ADMIN/AUDITOR | GET | `TRANSACTION_READ` + scope server-side; `fraudFlags` chỉ khi `FRAUD_READ`/`FRAUD_REVIEW` | Thiếu quyền/scope → 403 + C3; không trả fraud flags cho customer hoặc staff thiếu quyền. |
| Staff | POST | Không có quyền tạo transfer | 403. |

- Actor lấy từ principal đã xác thực (`userId`, `customerId`), không nhận từ body/query; `customerId` client truyền khác session → 403. Kiểm tra quyền cả filter/`@PreAuthorize` lẫn service (BE-CON-24/36, defense in depth).
- Permission seed theo `07` §3.4: OPERATIONS có `FRAUD_REVIEW`; ADMIN/AUDITOR có `FRAUD_READ`; customer không bao giờ có quyền staff.
- **Scope staff chưa cụ thể trong nguồn MVP:** DEC-TRF-012 yêu cầu scope xác định server-side nhưng cơ chế (bảng scope, gán phạm vi) chưa được mô tả. Phải thống nhất với API/Security/Backend; nếu cần contract mới, ghi vào `05` để review. Trước khi chốt, triển khai theo matrix `07` §3.5 và giữ AC scope **Blocked**, không tự bịa cơ chế scope.
- C3 dùng `recordStandalone`/`REQUIRES_NEW` best-effort sau quyết định deny, lỗi ghi fallback security log + metric/alert (TRFC-AU-02); không dùng log thay audit, không làm lộ transfer khác.
- Test bắt buộc: IDOR customer A đọc/ tạo trên nguồn customer B; GET không participant; staff thiếu permission/scope; hai user cùng customer; customer không nhận `fraudFlags`; deny có audit/fallback.

## 7. Kế hoạch kiểm thử và evidence

| Nhóm / liên kết | Thiết lập và oracle tối thiểu |
|---|---|
| Parser/validation unit | 8 test hiện có của `TransferCreateInput` và bổ sung boundary matrix `09` §5.2: amount 0/âm/scale >2/precision biên, destination thiếu/cả hai/sai kiểu, source = destination (ID và sau resolve), description độ dài/control/Unicode, key thiếu/sai pattern/độ dài, body quá 64 KB, UTF-8/JSON malformed, unknown field/duplicate key. Assert không side effect và message không lộ input. |
| Controller/contract | `CT-API-006` thiếu key → 400; `CT-API-007–009` replay/conflict/concurrent (cùng 04); `CT-API-010` 428 + verify retry một lần (cùng 04); `CT-API-011` 422 không ledger (cùng 05); `CT-API-015–017` IDOR/staff/PII. Kiểm status, body schema, header (`Location`, `Idempotent-Replayed`, `X-Correlation-ID`, `Retry-After`, `no-store`), `retryable`, masking và khớp OpenAPI; CI contract diff. |
| Session/CSRF thật | Wiring `SessionAuthenticator` identity thật: session hợp lệ/hết hạn/thu hồi → 200/401; cookie thiếu/sai CSRF → 403, Bearer miễn CSRF; GET không cần CSRF. Không dùng mock authenticator làm bằng chứng nghiệm thu. |
| Authorization/scope | AC-024, `CT-API-015/016`: cross-customer POST/GET, staff thiếu quyền, fraud flags ẩn với customer; deny 403 + C3/fallback; 403/404 đúng chính sách tồn tại; tham chiếu khó đoán và rate limit không bị coi là chống enumeration tuyệt đối. |
| Receipt/masking | AC-014, AC-023, `CT-API-017`: GET trả đủ reference/status/amount/timestamps/correlation, `AccountRef` mask `**** **** <last4>`, không full account/PII trong response/log; description chỉ participant/staff; `no-store`. Phối hợp `T-AUD-16` (PII scanner audit) khi policy §5 được duyệt. |
| Quota | 30 request/phút/customer: vượt → 429 + `Retry-After`; store không khả dụng → 503 fail-closed; test atomic đa instance với shared store thật. Limiter in-process chỉ chứng minh hành vi single-instance, không là bằng chứng quota toàn cụm (DEC-TRF-012). |
| Integration | Session thật + Account API thật (resolve blind index, ownership) + PostgreSQL và migration BE-TRF-02; POST end-to-end khi 04/05 sẵn sàng; GET trên dữ liệu thật đủ PENDING/COMPLETED/FAILED. Provider chưa có: fake test-only cùng vector `05` §13.4, kiểm mapping/rejection/không side effect; không đưa stub vào production. |
| Boundary kiến trúc | ArchUnit consumer-only-api (transfer chỉ import `provider.api`), controller không entity/transaction, fixture âm chứng minh rule bắt vi phạm; `./gradlew check` đạt. |

Mỗi bằng chứng theo `09` §10: test ID/phạm vi, revision, lệnh, môi trường, output và artifact synthetic không PII/secret, người chạy và reviewer. Test fake/fixture không gọi là end-to-end; thiếu provider thật, migration production hoặc môi trường ghi **Blocked** kèm owner. Kết quả parser/unit hiện có là evidence độc lập, không thay contract/security/integration.

## 8. Phối hợp, vấn đề mở và điều kiện đóng

| Vấn đề | Đầu mối | Ảnh hưởng / việc cần làm |
|---|---|---|
| Policy phát hiện PII trong description/key | BA/Security | Chưa có thuật toán duyệt; đề xuất scanner `design/01` §5.6; quyết định ghi `DECISIONS.md`, đồng bộ `07`/`04`; chặn AC PII của TRF-03.3. |
| Cơ chế data scope staff | API/Security/Backend | DEC-TRF-012 yêu cầu server-side scope; nguồn MVP chưa cụ thể; thống nhất và ghi `05` nếu contract mới; chặn phần AC scope TRF-03.5. |
| Format reference (OQ-02) | API owner | `07` đề xuất `TXN…`; xác nhận trước khi khóa validation GET và sinh reference (04). |
| Review OpenAPI/overview + replay correlation | API/FE/QA | YAML đã mô tả 201/422; cần review, CI diff và kiểm chứng implementation; correlation body gốc/header hiện tại (OI-CON-13). |
| Quota shared atomic | Ops/Backend/Common | DEC-TRF-012; triển khai shared store, test đa instance và 503 fail-closed trước vận hành nhiều instance. |
| Identity/Account thật | Đạt | `SessionAuthenticator` implementation và `account.api` chưa có; chặn AC session/ownership/integration; fake chỉ test/dev. |
| Outcome orchestrator | BE-TRF-04/05 | Adapter map outcome theo §4; end-to-end POST và replay/428/422 chỉ nghiệm thu khi 04/05 hoàn tất. |

**Điều kiện đóng task:** mọi AC trong [backlog](README.md) được Verified với session thật, account API thật và PostgreSQL; contract khớp `07`/YAML có review API/FE/QA; policy PII và scope staff đã quyết định hoặc ghi blocker release có owner; quota shared có bằng chứng đa instance; evidence tái lập theo `09` §10 và reviewer liên quan xác nhận. Không đánh dấu `[x]` chỉ vì tài liệu đã viết, parser test đạt hay fake đã pass; vấn đề chưa quyết định ghi **Blocked**, không xóa tiêu chí để đóng task.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 16/11/2026 | Chi tiết phạm vi, baseline HTTP/validation/quyền, vấn đề mở, test oracle và điều kiện đóng BE-TRF-03; chưa triển khai hoặc nghiệm thu. |
| v1.1 | 16/11/2026 | Triển khai boundary HTTP candidate (controller/DTO/query/service/C3, parser nối endpoint) theo DEC-TRF-016; 38 test mới + check 338 xanh; cập nhật hiện trạng và liên kết evidence. AC vẫn Blocked chờ provider thật, orchestrator 04/05, policy PII/scope staff, quota shared và review. |