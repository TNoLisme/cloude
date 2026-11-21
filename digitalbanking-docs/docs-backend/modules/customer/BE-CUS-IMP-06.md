# BE-CUS-IMP-06 — Staff `GET /api/v1/admin/customers/{customerId}` (API-ADM-01)

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework` (working tree chưa commit, giữ nguyên thay đổi IMP-01…05 + task song song)
> **Trạng thái:** **PARTIAL** — endpoint + permission + masking + audit-deny hoàn tất và test PASS; **scope enforcement BLOCKED bởi CUS-OI-05**, oracle mask fullName tạm thời theo DEC-CUS-005 chờ sign-off CUS-OI-02, persistence `audit_log` BLOCKED bởi module audit chưa có (giống DEC-ID-003).
> **Phụ thuộc:** [BE-CUS-IMP-04](BE-CUS-IMP-04.md) (binding `CustomerQuery`), [BE-CUS-IMP-03](BE-CUS-IMP-03.md) (PII decrypt/mask), Identity session authentication (`SessionAuthenticationFilter`, `JpaAuthorityReader`), Common security chain, `07` §3.4/§3.6/§8.2.4, design/05 §6, design/01 §5.2–§5.7.

## A. Phạm vi triển khai

- `customer.adapter.in.CustomerAdminDetailController` — `API-ADM-01` `GET /api/v1/admin/customers/{customerId}` (07 §8.2.4, openapi `/admin/customers/{customerId}`):
  - `customerId` validate theo schema `ID` (`[1-9][0-9]{0,18}`, openapi `CustomerIdPath`); sai định dạng → `400 VALIDATION_ERROR` trước khi chạm persistence (không lộ tồn tại).
  - Authentication (401) và coarse authorization (`hasAuthority("CUSTOMER_READ")`, 403) do security chain dùng chung thực thi trước handler — rule đã tồn tại sẵn trong `SecurityFilterChainConfig`, KHÔNG sửa chain.
  - Principal tin cậy từ attribute `SessionAuthenticationFilter.session`; KHÔNG nhận danh tính/role/scope từ query/body/header.
- `customer.application.StaffCustomerDetailService` — service của endpoint:
  1. Re-check `CUSTOMER_READ` từ authority snapshot tin cậy (defense-in-depth theo design.md §7 "kiểm soát quyền ở service, không chỉ security filter"); thiếu → `403 AUTH_FORBIDDEN` + audit `SECURITY_ACCESS_DENIED` **trước khi lookup** (chống enumeration: caller thiếu quyền không biết customer có tồn tại không).
  2. `customerId <= 0` → `400 VALIDATION_ERROR` trước lookup, không audit (design/01 §5.7: validation error chỉ technical log).
  3. Delegate `CustomerPiiService.getForStaff(customerId)` — row vắng → `404 CUSTOMER_NOT_FOUND` (không audit); Row `INACTIVE` vẫn 200 kèm status thực tế (07 §8.2.4; eligibility CUS-OI-06 chưa duyệt, không tự thêm điều kiện).
  4. Truy cập hợp lệ KHÔNG phát audit action riêng: design/01 §5.7 quy định view/search thành công chỉ ghi technical access log (`HttpRequestLog`) — không tự chế action `CUSTOMER_VIEWED`.
- `customer.application.CustomerPiiService.getForStaff` — projection staff: decrypt trong scope Customer (AES thật, Common primitive) rồi mask **TẤT CẢ** PII: `fullName` qua `maskFullName` mới, phone/email giữ đúng masking owner (`********123`/`n***@example.com`). Mask fail → fail-closed `INTERNAL_ERROR`, không bao giờ trả tên nguyên văn.
- `customer.application.StaffCustomerPiiView` — record bất biến cho staff, `toString()` redact; không password/credential/token/entity serialization.
- Response: tái sử dụng đúng schema `CustomerProfile` (07 §7.2.9, 8 field, openapi `#/components/schemas/CustomerProfile`) qua record `CustomerProfileResponse` dùng chung; `fullName` masked thay vì đầy đủ theo 07 §8.2.4 "Không trả full PII". KHÔNG thêm field (không KYC/CIF/envelope/correlationId trong body).

## B. Masking fullName (DEC-CUS-005)

| Vấn đề | Quyết định | Trạng thái |
|---|---|---|
| Oracle mask `fullName` cho staff (CUS-OI-02 chưa có thuật toán được duyệt) | Thuật toán bảo thủ tạm thời trong `common.crypto.PiiMaskingUtils.maskFullName`: mỗi token (phân tách whitespace, chuẩn hóa) dài ≥2 ký tự giữ ký tự đầu + `***`; token 1 ký tự che toàn bộ. `"Nguyễn Văn An"` → `"N*** V*** A***"`. Null-safe; input trống → fail-closed `INTERNAL_ERROR`. | TẠM THỜI — CUS-OI-02 vẫn OPEN, chờ API/BA/Security chốt oracle rồi cập nhật + test vector |
| Contact info | Phone/email masked như owner projection (07 §8.2.4: fullName/phone/email đã mask; OI-API-09 full PII nằm ngoài MVP) | PASS |

## C. Audit (SECURITY_ACCESS_DENIED, C3)

- `customer.application.CustomerAuditPort` + `customer.adapter.out.CustomerSecurityLogAuditAdapter` — pattern đã duyệt của identity (DEC-ID-003, `SecurityLogAuditAdapter`): C3 standalone best-effort, emit security log (`com.simulatedbank.banking.customer.AUDIT`) + metric `customer.audit.events{action,outcome}`/`customer.audit.failures{action}`; lỗi bị nuốt + fallback log, KHÔNG đổi response 403. Persistence `audit_log` thuộc module audit chưa có → BLOCKED (giống BE-ID-IMP-13); adapter giữ nguyên semantics cho integration sau (allowlist design/01 §5.6: entity `CUSTOMER` chỉ `customerId`/`status`/`reasonCode` — không tên/phone/email).
- `common.config.SecurityAccessDeniedAuditor` (mới, Common) — ghi `SECURITY_ACCESS_DENIED` cho các lượt 403 bị từ chối ở security chain dùng chung (`AuthorizationDeniedEvent` mà Spring Security phát mặc định cho quyết định DENY của `AuthorizationFilter`), bỏ lỗ hổng đã ghi BLOCKED tại BE-ID-IMP-13 §8:
  - Chỉ record khi principal là snapshot `SessionAuthenticator.Session` (→ 403 sau xác thực); deny của caller vô danh (→ 401 entry point) và lỗi trước `AuthorizationFilter` không phát sự kiện (design/01 §5.7: 401 không audit).
  - Metadata: `actor_user_id` (số, từ snapshot), `actor_roles` (ROLE_* đã strip), `entity_type` từ bảng ánh xạ đóng (`customers`→`CUSTOMER`, …, không khớp → `UNKNOWN` — label metric bounded, không chứa input client), `entity_id` chỉ khi segment số `[1-9][0-9]{0,18}`, `reason_code=AUTH_FORBIDDEN`, outcome `REJECTED`. KHÔNG log PII/token/UA/query.
  - Metric `security.audit.events{action,outcome,entity_type}`; emit trong try/catch — sự kiện audit không được làm hỏng response 403 (C3).
- Phạm vi evidence: audit deny được chứng minh bằng metric (persistence chưa có); test xác nhận counter tăng khi CUSTOMER và staff thiếu permission bị từ chối.

## D. Kiểm thử và bằng chứng (chạy từ `digitalbanking-backend`)

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.application.StaffCustomerDetailServiceTest' \
  --tests 'com.simulatedbank.banking.common.crypto.PiiMaskingUtilsTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.*' --tests 'com.simulatedbank.banking.architecture.*' \
  --tests 'com.simulatedbank.banking.common.*' --console=plain
./gradlew build --max-workers=1 --console=plain
```

- `PiiMaskingUtilsTest` — **17/17 PASS** (2 test mới: mask chỉ giữ ký tự đầu mỗi token, token 1 ký tự che toàn bộ, chuẩn hóa whitespace, null-safe, blank fail-closed).
- `StaffCustomerDetailServiceTest` — **8/8 PASS** (AES thật + port in-memory + fake audit port): staff hợp lệ nhận projection mask đủ 3 field + không audit; CUSTOMER bị 403 TRƯỚC lookup (`findByIdCalls = 0`, `decryptCalls = 0`) + audit event đúng metadata (action/actor/role/entity/reason/correlation); staff thiếu `CUSTOMER_READ` bị từ chối + audit; snapshot authority vắng → fail-closed 403; `customerId ≤ 0` → 400 không audit không lookup; row vắng → 404 không audit; INACTIVE trả status thật + contact null; view/audit không rò PII qua `toString()`/giá trị.
- `CustomerAdminDetailControllerPostgreSqlIntegrationTest` — **9/9 PASS** trên PostgreSQL 16 Testcontainers, Flyway V1–V5 thật, security chain thật, HTTP thật qua random port: OPERATIONS 200 đúng 8 field + mask + `no-store` + correlation echo + không rò raw PII; ADMIN 200 (mask, contact vắng → JSON null); anonymous 401; CUSTOMER 403 + metric `security.audit.events{entity_type=CUSTOMER}` tăng ≥1 + response không tiết lộ PII; AUDITOR (seed A.2 không có `CUSTOMER_READ`) 403 + audit; `abc`/`0`/`99999999999999999999` → 400 `VALIDATION_ERROR`; ID lạ → 404 `CUSTOMER_NOT_FOUND`; INACTIVE → 200 status thật; GET không đổi ciphertext/`updated_at`/status (read-only thật).
- Focused regression `customer.*` + `architecture.*` + `common.*` — **29 test classes / 277 tests PASS** (bao gồm ArchUnit 9/9: common không import module nghiệp vụ sau khi thêm `SecurityAccessDeniedAuditor`; api không String-PII; không cycle).
- Full backend regression: xem phần E.

## E. Full backend regression

- `./gradlew build --max-workers=1 --console=plain` — lần 1 (19:17Z): **FAILED** — `identity.IdentityAuthPostgreSqlIntegrationTest.t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate` OOM `Java heap space` tại `Argon2BytesGenerator` (5 Argon2id đồng thời 64 MB/hash trong test worker heap mặc định 512 MB khi các context Testcontainers khác vẫn cache trong cùng JVM — cùng lớp flake tài nguyên đã ghi tại DEC-ID-TEST-02 và BE-CUS-IMP-04 §E). Class chạy **isolated: 25/25 PASS** trong 26 s; t11 không tham chiếu bean nào của Customer/Common-IMP-06.
- Lần 2 (rerun full): FAILED cùng t11 OOM (flake tái hiện khi worker vừa gánh nhiều context).
- Lần 3 (rerun full, môi trường nhàn rỗi): **BUILD SUCCESSFUL in 5m 41s — 68 test classes / 893 tests / 0 failure / 0 error / 0 skipped**. Hóa giải baseline IMP-05 (874) + 19 test mới của IMP-06 (8 unit staff + 2 masking + 9 HTTP/PostgreSQL integration); `IdentityAuthPerformanceTest` tiếp tục được tag `performance` và loại khỏi `build` mặc định.
- Kết luận: 2 lần FAILED đầu là flake tài nguyên đã ghi nhận của identity, không liên quan thay đổi của IMP-06 (không tăng tải Argon2; các suite customer/common/architecture PASS mọi lần). Đề xuất riêng cho owner Identity/TEST-03: cân nhắc `maxHeapSize` cho task `test` giống `perfTest` (3g) — không tự sửa build.gradle.kts dùng chung trong task này.

## F. Acceptance criteria (task)

| Tiêu chí | Trạng thái | Evidence |
|---|---|---|
| CUSTOMER denied (403) | PASS | Integration test 4 + unit "customerRoleIsDeniedBeforeAnyLookupOrDecryption" |
| Staff permission enforced (chain + service) | PASS | Chain rule `CUSTOMER_READ` sẵn có; service re-check unit test "staffWithoutRequiredPermissionIsDeniedAndAudited"; AUDITOR seed → 403 (test 5) |
| Staff scope enforced | **BLOCKED** | CUS-OI-05: Admin/Audit/Security chưa chốt data-scope runtime — không tự chế scope; chỉ permission đã duyệt được enforce (§G.1) |
| Masked `fullName` | PASS (tạm) | `maskFullName` DEC-CUS-005; CUS-OI-02 chờ oracle owner (§B) |
| Authorized contacts returned correctly | PASS | phone/email mask đúng `********123`/`n***@example.com`; vắng → JSON null |
| Denied access audited | PASS (mechanism), BLOCKED (persistence) | Metric + security log C3; `audit_log` thuộc module audit chưa có (§C) |
| API tests passed | PASS | Integration 9/9 |
| Security tests passed | PASS | Unit 8/8 + ArchUnit + no-PII-leak assertions |
| Relevant regression tests passed | PASS | Focused 277/277; full build 893/893 PASS (§E — 2 lần OOM flake identity đã phân tích) |
| Documentation updated | PASS | README, tasks checklist, DEC-CUS-005, design/05 §6.3, design.md §6, common CURRENT-STATE, BE-CUS-IMP-06.md |

## G. Dependency và blockers còn mở

1. **Staff data scope (CUS-OI-05) — BLOCKED:** hợp đồng scope Admin (`07` §3.6 "Staff đọc ngoài scope", design/05 §6 `getCustomerView(customerId, actor/scope)`) chưa có runtime/model nào trong code; không tự phát minh scope (không thêm permission/cột/config). Hiện chỉ enforce permission `CUSTOMER_READ` đã duyệt. Impact: mọi staff có `CUSTOMER_READ` đọc được mọi customer row (đúng §3.5 "read theo phạm vi" nhưng chưa có biên phạm vi thật). Responsible: Admin/Security/BA owner chốt scope model + audit policy; mở lại trong BE-CUS-IMP-07/08.
2. **Oracle mask fullName (CUS-OI-02) — OPEN:** thuật toán DEC-CUS-005 là bảo thủ tạm thời; đổi oracle → cập nhật `PiiMaskingUtils.maskFullName` + test vectors. Responsible: API/BA/Security.
3. **Audit persistence — BLOCKED:** module audit chưa có `AuditModuleApi`/bảng `audit_log` (DEC-ID-003, BE-ID-IMP-13); deny audit hiện là security log + metric (C3 best-effort) đúng semantics hợp đồng. Responsible: Audit owner; khi có, thay adapter bằng write `audit_log` (allowlist §5.6).
4. **AUDITOR seed vs 07 §8.2.4 — CONFLICT ghi nhận:** `07` §8.2.4 ghi "Operations/Admin/Auditor với CUSTOMER_READ" nhưng seed tối thiểu (`03` §A.2/`04` §A.2, nguồn SRS 4.2 chính thức) không cấp `CUSTOMER_READ` cho AUDITOR → AUDITOR nhận 403 trên endpoint này (test 5, fail-closed). Không tự sửa seed/migration của Identity. Responsible: API/Identity/DB owner phân xử; nếu cấp thêm permission thì endpoint mở cho AUDITOR mà không cần đổi code.
5. **Admin module runtime chưa có:** không có Admin controller/API thật để điều phối; endpoint staff thuộc owner Customer theo design.md §6 (API-ADM-01 trong nhóm Customer/Account). Admin orchestrator tương lai dùng đúng path này.

## Change Log

| Ngày | Mô tả |
|---|---|
| 17/11/2026 | Thêm `CustomerAdminDetailController`, `StaffCustomerDetailService`, `CustomerPiiService.getForStaff`, `StaffCustomerPiiView`, `CustomerAuditPort` + adapter, `PiiMaskingUtils.maskFullName`, `SecurityAccessDeniedAuditor` (Common); unit 8/8 + masking 17/17 + integration 9/9 + focused 277/277 + full build 893/893 PASS (lần 1/2 FAILED do OOM flake identity t11 — §E); PARTIAL — scope BLOCKED (CUS-OI-05), mask oracle tạm (CUS-OI-02), audit persistence BLOCKED (module audit) |
