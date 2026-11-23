# BE-CUS-IMP-05 — `GET /api/v1/customers/me` + Customer Profile Service

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework` (working tree chưa commit, giữ nguyên các thay đổi IMP-01…04 và task song song)
> **Trạng thái:** DONE — unit 10/10, HTTP/PostgreSQL integration 11/11, focused suites (customer + architecture + common) 258/258 PASS; full backend regression xem phần E.
> **Phụ thuộc:** [BE-CUS-IMP-04](BE-CUS-IMP-04.md) (binding `CustomerQuery.findByUserId`), Identity session authentication (BE-ID-IMP-02/03, `SessionAuthenticator` SPI), [DEC-CUS-002](../../../../DECISIONS.md), [DEC-CUS-004](../../../../DECISIONS.md).

## A. Phạm vi triển khai

- `customer.adapter.in.CustomerProfileController` — `API-CUS-01` `GET /api/v1/customers/me` (07 §8.2.1, openapi `/customers/me`):
  - Lấy principal tin cậy từ attribute `SessionAuthenticationFilter.session` của security chain (cùng cơ chế `AuthController`); không nhận `customerId`/`userId` từ query/body/header client.
  - 401 (thiếu/invalid/expired credential) và 403 (thiếu authority `ACCOUNT_READ_SELF`) do security chain dùng chung thực thi **trước** handler; handler fail-closed (`AUTH_SESSION_EXPIRED`) nếu snapshot phiên vắng mặt.
  - Response 200 + `Cache-Control: no-store`; header/body `X-Correlation-ID` do `CorrelationAndRequestIdFilter`/`ProblemWriter` dùng chung bảo đảm — controller không nhân bản filter.
- `customer.adapter.in.CustomerProfileResponse` — record đúng 8 field hợp đồng `CustomerProfile` (07 §7.2.9): `customerId`, `userId` decimal string; `fullName` đầy đủ cho owner; `phoneMasked`/`emailMasked` nullable; `status` enum `ACTIVE`/`INACTIVE`; `createdAt`/`updatedAt` Instant UTC. Không thêm field (không correlationId trong body, không KYC/CIF, không envelope).
- `customer.application.CustomerProfileService` — service duy nhất của endpoint:
  1. Nhận `actorUserId` từ principal tin cậy (do controller truyền; service không đọc HTTP).
  2. Resolve binding đã duyệt `customer.api.CustomerQuery.findByUserId(actorUserId)` (IMP-04) — **không** giả định userId == customerId.
  3. Mapping vắng → `404 CUSTOMER_NOT_FOUND`; không tự tạo Customer row, không placeholder, không binding mới (GET thuần đọc).
  4. `CustomerPiiService.getForOwner(customerId, actorUserId)` (IMP-03) — tái kiểm ownership trước khi decrypt (defense-in-depth: mapping hỏng → `403 AUTH_FORBIDDEN`, không bao giờ rò PII customer khác), decrypt + mask trong scope owner-safe.
  5. Transaction `readOnly = true` phủ toàn bộ chuỗi; 2 SELECT (mapping theo `user_id`, row theo `customer_id`) trong một read-only TX trên port đã duyệt — không mở port/query mới.
- Không thêm migration, không đổi security chain (rule `/api/v1/customers/me → ACCOUNT_READ_SELF` đã có sẵn trong `SecurityFilterChainConfig`), không sửa Identity, không thêm error format.

## B. Bảng quyết định hợp đồng (DEC-CUS-004)

| Vấn đề | Quyết định | Lý do |
|---|---|---|
| Nguồn actor | `Session` snapshot từ security chain; controller parse `Long.parseLong` | Format `[1-9][0-9]{0,18}` đã được `Session` record validate; DEC-CUS-002 "lấy actor từ principal tin cậy" |
| Ánh xạ Identity→Customer | `CustomerQuery.findByUserId` (IMP-04) | Hợp đồng đã duyệt design/05 §6; `Session.customerId` hiện `null` thuộc owner Identity (BE-ID-IMP-02) |
| 401/403 | Security chain dùng chung (`AUTH_SESSION_EXPIRED`/`AUTH_FORBIDDEN`) | Không tự tạo cơ chế auth; không bypass RBAC |
| 404 vs 403 | Mapping vắng → 404 `CUSTOMER_NOT_FOUND`; mapping sai owner → 403 `AUTH_FORBIDDEN` | 07 §8.2.1; "không thấy" không được giả thành lỗi kỹ thuật, lỗi kỹ thuật không được giả thành 404 |
| INACTIVE | Vẫn 200 kèm status thực tế | `CustomerProfile.status` gồm INACTIVE; eligibility chưa được duyệt (CUS-OI-06) — không tự thêm điều kiện từ chối |
| Correlation ID | `CorrelationAndRequestIdFilter` + `ProblemWriter` (header, body Problem, `HttpRequestLog`) | Tái sử dụng infra dùng chung, không nhân bản |
| Query param/client header | Bị bỏ qua hoàn toàn (endpoint không nhận tham số danh tính) | Chống IDOR/override theo cấu trúc hợp đồng |

## C. Kiểm thử và bằng chứng (chạy từ `digitalbanking-backend`)

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.application.CustomerProfileServiceTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerProfileControllerPostgreSqlIntegrationTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.*' --tests 'com.simulatedbank.banking.architecture.*' --tests 'com.simulatedbank.banking.common.*' --console=plain
./gradlew build --console=plain
```

- `CustomerProfileServiceTest` — **10/10 PASS** (composition thật IMP-04 + IMP-03 trên port in-memory, không mock logic bảo mật): binding tra đúng 1 lần theo actor; fullName đầy đủ + liên hệ mask; contact vắng → null; mapping vắng → `CUSTOMER_NOT_FOUND` và không chạm PII; `userId <= 0` → `VALIDATION_ERROR` trước lookup; ánh xạ hỏng (user 202 map vào customer của 101) → `AUTH_FORBIDDEN`, `decryptCalls = 0`, không ghi; INACTIVE vẫn trả hồ sơ kèm status; 2 lần đọc không đổi store/`updated_at`; lỗi persistence (`QueryTimeoutException`) lan truyền nguyên trạng, không bị reclassify thành 404; ciphertext hỏng → `INTERNAL_ERROR` fail-closed không rò chi tiết.
- `CustomerProfileControllerPostgreSqlIntegrationTest` — **11/11 PASS** trên PostgreSQL 16 Testcontainers, Flyway V1–V5 thật, security chain thật (`SessionAuthenticationFilter`, RBAC seed V2, rate-limit, correlation filter), HTTP thật qua random port, không mock bỏ bước bảo mật:
  1. Owner có session hợp lệ → **200** đúng JSON contract: đủ 8 field, không field thừa; `customerId`/`userId` khớp row; fullName đầy đủ; `********123`/`n***@example.com`; `X-Correlation-ID` echo, `Cache-Control: no-store`, timestamps parse về Instant khớp DB.
  2. Anonymous → **401** `AUTH_SESSION_EXPIRED` (Problem JSON + `WWW-Authenticate`).
  3. Token lạ → **401**.
  4. Session `idle_expires_at` quá hạn → **401** (row customer vẫn tồn tại, không bị đụng).
  5. Staff `OPERATIONS` (chỉ `ACCOUNT_READ`) → **403** `AUTH_FORBIDDEN`.
  6. User `CUSTOMER` chưa có Customer row → **404** `CUSTOMER_NOT_FOUND`, count customer/app_user không đổi, session không bị tạo thêm (no-store).
  7. User không binding query `?customerId=<người khác>&userId=<người khác>` → **404**, body không rò tên/email/số điện thoại của customer khác (anti-enumeration).
  8. `X-Correlation-ID` có khoảng trắng → **400** `VALIDATION_ERROR` từ middleware dùng chung (trước security).
  9. Không gửi header → server sinh UUID, echo lại trong response.
  10. Sau GET: `updated_at`, ciphertext 3 cột, status không đổi (read-only thật).
  11. Row INACTIVE → **200** `status: INACTIVE`, `phoneMasked`/`emailMasked` là JSON `null`.
- Focused regression `customer.*` + `architecture.*` + `common.*` — **258 tests PASS, 0 fail** (bao gồm ArchUnit 9/9: api không String-PII, không import internals, không cycle).

## D. Security verification

| Tiêu chí | Kết quả | Evidence |
|---|---|---|
| Authentication enforcement | PASS | Test 2/3/4 (401 qua chain thật, không controller tự check) |
| Self-ownership / no IDOR | PASS | Endpoint không nhận tham số danh tính; test 1 (query param bị bỏ qua), test 7 (không rò customer khác); ownership tái kiểm trong `getForOwner` |
| Identity→Customer binding đúng | PASS | Dùng đúng `CustomerQuery.findByUserId` (IMP-04); không suy mapping từ bảng khác |
| Tenant isolation | N/A v1 | `banking.customer` không có tenant column trong 04 §4.2.2 — không tự thêm; isolation hiện tại = ownership 1:1 user↔customer |
| No-store khi 404 | PASS | Test 6 (không tạo row, không placeholder); `ApiSecurityHeadersFilter` đặt `no-store` mọi response |
| Information leakage | PASS | Problem bodies chỉ mã chuẩn; test 7 assert không rò PII; ciphertext/plaintext không vào log/response |
| Logging an toàn | PASS | `HttpRequestLog` ghi `maskedUserId`; controller/service không log PII/token |
| Read-only | PASS | `readOnly = true` TX + test 10 (DB bytes không đổi) |

## E. Full backend regression

- `./gradlew build --console=plain` — **BUILD SUCCESSFUL in 6m 28s — 66 test classes / 874 tests / 0 failure / 0 error**. Hóa giải baseline IMP-04 (853) + 21 test mới của IMP-05 (10 unit + 11 HTTP/PostgreSQL integration) = 874; `IdentityAuthPerformanceTest` tiếp tục được tag `performance` và loại khỏi `build` mặc định (build.gradle.kts của BE-ID-TEST-03). Không test nào của task song song fail trong lần chạy này; không cần rerun riêng.

## F. Acceptance criteria (task)

| Tiêu chí | Trạng thái | Evidence |
|---|---|---|
| HTTP 200 đúng `CustomerProfile` | PASS | Integration test 1 (field set, types, mask, Instant) |
| 401 missing/invalid/expired | PASS | Tests 2/3/4 qua chain thật |
| 403 authenticated nhưng thiếu quyền | PASS | Test 5 (OPERATIONS thiếu `ACCOUNT_READ_SELF`) |
| 404 không có Customer row, no-store | PASS | Test 6 + count trước/sau |
| Correlation ID propagation | PASS | Tests 1/8/9 (echo, reject sai, sinh mới) |
| Strict self-ownership | PASS | Tests 1/7 + ownership re-check trong service |
| Không đổi state khi GET | PASS | Tests 6/10 + `readOnly` TX |

## G. Dependency và giới hạn còn mở

- `Session.customerId` vẫn `null` — wiring `AuthResponse.customerId` thuộc owner Identity (BE-ID-IMP-02, giờ có thể unblock qua `CustomerQuery`); Customer không import Identity (ArchUnit no-cycle PASS).
- Tenant isolation: schema v1 (04 §4.2.2) không có tenant — không tự thêm.
- IMP-06 (staff view `getCustomerView`) vẫn blocked bởi CUS-OI-02/05.
- Provisioning: không có API create hợp đồng — luồng self-only dùng row đã tồn tại; tạo row vẫn thuộc BE-CUS-IMP-09 (BLOCKED theo DEC-CUS-002).

## Change Log

| Ngày | Mô tả |
|---|---|
| 17/11/2026 | Thêm `CustomerProfileController` + `CustomerProfileResponse` + `CustomerProfileService`; 10 unit + 11 HTTP/PostgreSQL integration PASS; focused 258/258; full build 66 classes/874 tests PASS → DONE. |
