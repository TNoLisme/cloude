# BE-ACC-09 — Staff-scoped list `API-ADM-02`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-09
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** **PARTIAL** — endpoint `GET /api/v1/admin/customers/{customerId}/accounts` đã có runtime + test HTTP/PostgreSQL; còn BLOCKED hai phần hợp đồng: data scope staff (ACC-DR-13) và `404 CUSTOMER_NOT_FOUND` (ACC-DR-10)
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần BA + Security + owner Admin/Customer
> **Branch:** `datht-backend-rework`; không tạo/switch branch, không commit/push
> **Nguồn tham chiếu:** `docs-common/07. API contracts.md` §8.2.2 (rule dùng chung) và §8.2.5 (`API-ADM-02`); `docs-common/design/05. Modules contract.md` §6.2/§7.1; `docs-common/design/04. security.md`; `docs-common/04. Database design & analysis.md` §4.3.1; OpenAPI `/admin/customers/{customerId}/accounts`; [BE-ACC-01](BE-ACC-01.md) §C/§E; [BE-ACC-05](BE-ACC-05.md); `admin/design.md` §7 (ADM-OI-06); [DEC-ACC-008](../../../../DECISIONS.md)

---

## A. Phạm vi đã triển khai

| Hạng mục | Kết quả |
|---|---|
| Route | `GET /api/v1/admin/customers/{customerId}/accounts` — một controller duy nhất `account.adapter.in.AdminCustomerAccountsController` (ACC-DR-11: Account sở hữu route, Customer giữ `API-ADM-01`) |
| Authorization | Security chain `hasAuthority("ACCOUNT_READ")` (rule đã tồn tại, không sửa) + service re-check `ACCOUNT_READ` từ authority snapshot `SessionAuthenticationFilter` |
| Scope | Chỉ trả account của `customerId` trong path; actor/scope không đọc từ query/body. **Data scope chi nhánh/segment chưa có model** → ACC-DR-13 BLOCKED |
| Response | `AccountsResponse{items[AccountSummary], asOf}` đúng `07` §8.2.2/OpenAPI; số tài khoản masked `**** **** <last4>`; money string scale 2; `Cache-Control: no-store` |
| Filter | `status` optional `ACTIVE\|LOCKED\|CLOSED` theo rule đã chốt của `API-ACC-01`; giá trị khác → 400 trước persistence |
| Pagination/sort | **Không** implement — `07`/OpenAPI không định nghĩa `page/size/cursor/sort` cho route này |
| ID validation | `customerId` theo schema `ID` `[1-9][0-9]{0,18}`; sai → `400 VALIDATION_ERROR` trước persistence |
| 404 `CUSTOMER_NOT_FOUND` | **Chưa phát được** — Customer chưa có operation existence theo `customerId` (ACC-DR-10) |
| Database | Không migration, không cột/index mới; dùng `ix_account_customer` + `(customer_id, status)` sẵn có của `V8` |
| Cross-module | Không thêm operation `customer.api`, không đọc bảng Customer/Account từ module khác, không nới rule security chain |
| Idempotency | Không áp dụng — GET read-only, side-effect-free (`07` §8.2.5, BE-ACC-01 §B.3) |

---

## B. Quyết định triển khai (DEC-ACC-008)

| # | Quyết định | Căn cứ | Hệ quả |
|---|---|---|---|
| 1 | Route do Account implement; không tạo controller thứ hai cùng path | ACC-DR-11 resolved; DEC-CUS-006 (Customer không đọc bảng account) | Không xung đột mapping với `CustomerAdminDetailController` (`/{customerId}`) |
| 2 | `ACCOUNT_READ` là gate duy nhất hiện có; service re-check từ principal snapshot | `07` §8.2.5; security chain hiện hữu; pattern `StaffCustomerDetailService` | Defense-in-depth; không có cơ chế authorization thứ hai tự chế |
| 3 | Không kiểm ownership cá nhân, không thêm điều kiện chi nhánh/segment | `07` §8.2.5; ACC-DR-13 BLOCKED; ADM-OI-06/CUS-OI-05 | Staff có `ACCOUNT_READ` đọc được account của mọi customer (đúng hiện trạng đã ghi ở BE-CUS-IMP-06); **không** coi là production-complete |
| 4 | `customerId` chỉ là đối tượng tra cứu, không phải danh tính actor | anti-IDOR; `design/05` §3.1 | Không suy `userId/customerId` từ client; actor chỉ lấy từ session |
| 5 | Giữ `status` filter và từ chối giá trị lạ trước persistence | BE-ACC-01 §C rule 2 ("status filter optional… giá trị khác → 400") + `07` §8.2.5 ("quy tắc như `API-ACC-01`") | Nhất quán với `API-ACC-01`; không thêm param ngoài hợp đồng |
| 6 | Không pagination/sort | OpenAPI route chỉ có `CorrelationId` + `CustomerIdPath`; BE-ACC-01 §C rule 6 | Không tạo contract mới; thứ tự ổn định `account_id ASC` từ index |
| 7 | Không thêm operation Customer existence | `design/05` §6.2 `getCustomerView` chưa duyệt/triển khai; ACC-DR-10 | Customer không tồn tại trả `200 {items: [], asOf}` giống customer chưa có account; ghi limitation, không fail-closed giả |
| 8 | Dùng `AccountHttpSupport` dùng chung với `AccountController` | tránh hai bản parse/money/masking | Hành vi `API-ACC-01/02` không đổi (17/17 regression pass) |

---

## C. Hành vi API

| Input | Kết quả |
|---|---|
| Thiếu/invalid/expired session | `401 AUTH_SESSION_EXPIRED` (security chain) |
| CUSTOMER chỉ có `ACCOUNT_READ_SELF` | `403 AUTH_FORBIDDEN` (chain) |
| Staff thiếu `ACCOUNT_READ` | `403 AUTH_FORBIDDEN` + C3 deny audit của chain (`SecurityAccessDeniedAuditor`) |
| Staff/auditor có `ACCOUNT_READ` | `200 AccountsResponse` chỉ gồm account của `customerId` path |
| `customerId` sai `ID` pattern / vượt `BIGINT` | `400 VALIDATION_ERROR`, không chạm persistence |
| `status` ngoài enum (kể cả rỗng) | `400 VALIDATION_ERROR` |
| Customer không tồn tại | Hiện tại `200 {items: [], asOf}` — **deviation** do ACC-DR-10 |
| Customer không có account | `200 {items: [], asOf}` (đúng hợp đồng) |
| Lỗi persistence/crypto | Lan truyền technical → 500/503 qua Common problem writer; không nuốt thành rỗng |

- `asOf` cấp response và `asOf` từng item là cùng một snapshot (`Clock` của service); không cache.
- Không trả plaintext/ciphertext/blind index, không trả `owner.fullName` (route này dùng `AccountSummary`, không dùng `AccountDetail`).

---

## D. Bảo mật & data isolation

| Rủi ro | Kiểm soát | Evidence |
|---|---|---|
| IDOR qua `customerId`/`userId` query | Không có tham số danh tính nào ngoài path; actor lấy từ session snapshot; path chỉ chọn tập account | `targetCustomerIdIsBoundByThePathAndNeverFallsBackToTheActorIdentity` |
| Cross-customer leak | Response chỉ chứa row `customer_id = path`; test seed 2 customer và xác nhận account ngoài path không xuất hiện | `staffListReturnsOnlyTheTargetCustomerAccountsWithTheExactMaskedContract` |
| Privilege escalation | Chain `ACCOUNT_READ` + service re-check; CUSTOMER (chỉ `ACCOUNT_READ_SELF`) bị 403 | `customerWithOnlySelfReadIsForbidden`, `staffListRejectsACallerWithoutAccountReadBeforePersistence` |
| Anonymous/expired | Shared session filter fail-closed | `anonymousCallerIsRejected` |
| PII/financial leak qua log/response | Masked projection + money string; không hash/ciphertext | `doesNotContain("0011111111", …)`; field-set assertion |
| Cross-tenant | **Chưa có tenant trong schema v1** (ghi nhận ở Admin DB-03); scope tổ chức thuộc ACC-DR-13 | BLOCKED |
| Read side effect | GET không update row/balance | `theEndpointIsReadOnly` |

**Audit khi deny:** HTTP deny được audit một lần bởi `SecurityAccessDeniedAuditor` của chain (C3 `SECURITY_ACCESS_DENIED`, chỉ sau khi đã xác thực). Re-check trong service là defense-in-depth cho internal caller; trên đường HTTP nó không thể là nguồn deny duy nhất (chain đã chặn trước handler), nên service không phát thêm event trùng và không mở rộng `AccountAuditPort` khi chưa có action/allowlist được duyệt (ACC-DR-15).

---

## E. Test coverage

| Lớp | Test | Kết quả |
|---|---|---|
| Unit | `AccountAccessServiceTest` (4 test mới: scope/order/mask, status, thiếu permission, invalid input) | **12/12 PASS** |
| HTTP + PostgreSQL 16 | `AdminCustomerAccountsControllerPostgreSqlIntegrationTest` | **11/11 PASS** |
| Regression `API-ACC-01/02` | `AccountControllerPostgreSqlIntegrationTest` (sau refactor `AccountHttpSupport`) | **17/17 PASS** |
| Regression read service | `AccountReadPostgreSqlIntegrationTest` | **10/10 PASS** |
| Architecture | `ModuleBoundaryArchTest` | **18/18 PASS** |
| Context | `BackendApplicationContextTest` | **1/1 PASS** |
| Security chain regression | `HttpSecurityTest`, `HttpSecurityServletTest`, `ObservabilityIntegrationTest` | **10/10 + 2/2 + 6/6 PASS** |

Lệnh chạy:

```bash
./gradlew test --tests 'com.simulatedbank.banking.account.*' --max-workers=1
./gradlew test --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --max-workers=1
```

Môi trường: PostgreSQL 16 Testcontainers, Flyway `V1`–`V8`, security chain và session filter thật (không mock authorization).

---

## F. Files thay đổi

| File | Thay đổi |
|---|---|
| `account/application/AccountAccessService.java` | thêm `listCustomerAccounts(...)` + `STAFF_READ_PERMISSION`; đổi tên record kết quả `OwnAccounts` → `AccountList` (nội bộ application) |
| `account/adapter/in/AdminCustomerAccountsController.java` | **mới** — REST adapter `API-ADM-02` |
| `account/adapter/in/AccountHttpSupport.java` | **mới** — parse ID/status, principal, correlation, money, summary mapping dùng chung |
| `account/adapter/in/AccountController.java` | dùng `AccountHttpSupport`; không đổi route/DTO/status code |
| `test/.../account/application/AccountAccessServiceTest.java` | 4 test staff list |
| `test/.../account/adapter/in/AdminCustomerAccountsControllerPostgreSqlIntegrationTest.java` | **mới** — 11 test HTTP/authorization/PostgreSQL |

Không sửa `V1`–`V8`, không sửa module Customer/Admin, không đổi security chain.

---

## G. Giới hạn & blocker còn lại

| ID | Nội dung | Trạng thái | Cần ai chốt |
|---|---|---|---|
| ACC-DR-13 | Data scope staff (chi nhánh/segment/purpose) cho list/detail; hiện chỉ permission `ACCOUNT_READ` | **BLOCKED** | BA + Security + Admin (ADM-OI-06/CUS-OI-05) |
| ACC-DR-10 | `404 CUSTOMER_NOT_FOUND` cho route này cần operation Customer existence/masked view theo `customerId` (`design/05` §6.2 `getCustomerView`) | **BLOCKED** | owner Customer + BA/Security |
| ACC-DR-15 | Durable `audit_log` C3 (hiện structured log + metric; deny HTTP do chain audit) | **BLOCKED** | owner Audit |
| — | Cross-tenant/NCC organization scope | **BLOCKED** | schema v1 không có tenant, cần quyết định schema |

**Deviation hiện tại (đã ghi nhận, không che):** khi Customer chưa có provider existence, route trả `200 {items: [], asOf}` cho `customerId` không tồn tại. Không lộ dữ liệu và không cấp quyền thêm, nhưng **không đúng** error semantics `404 CUSTOMER_NOT_FOUND` của `07` §8.2.5.

---

## H. Acceptance criteria status

| Tiêu chí | Trạng thái |
|---|---|
| Endpoint đúng path/permission/response schema | **PASS** |
| Masked account number, money scale 2, `no-store` | **PASS** |
| Chặn CUSTOMER/thiếu permission/anonymous | **PASS** |
| Chỉ trả account của `customerId` path, không cross-customer | **PASS** |
| Validation `customerId`/`status` trước persistence | **PASS** |
| `404 CUSTOMER_NOT_FOUND` | **BLOCKED** (ACC-DR-10) |
| Staff data scope (chi nhánh/segment) | **BLOCKED** (ACC-DR-13) |
| Pagination/sort ngoài hợp đồng | **Không implement (đúng)** |

Kết luận: `BE-ACC-09` = **PARTIAL**. Không đánh dấu DONE khi scope và 404 chưa có hợp đồng/runtime.
