# BE-ADM-API-04 — Customer/Account operational lookup integration

> **Trạng thái:** **DONE (18/11/2026, `datht-backend-rework`)** — provider runtime thật, authorization/scope theo contract đã duyệt, C3 audit và test PostgreSQL/HTTP thật PASS.
>
> **Giới hạn còn mở (không chặn task):** data scope staff theo chi nhánh/segment/tenant (`ADM-OI-06`, `ACC-DR-13`, `CUS-OI-05`) chưa có model được duyệt. Task này **không** tự chế scope; gate hiện tại là permission + fail-closed trên identity context không nhất quán. Chi tiết §9.
>
> **Task liên quan:** `BE-ADM-SEC-01` (cùng đợt hardening authorization), `BE-CUS-IMP-06`, `BE-ACC-09`.

## 1. Nguồn contract

- `docs-common/07. API contracts.md` §3.4–§3.6 (RBAC, ownership/data scope), §8.2.4 `API-ADM-01`, §8.2.5 `API-ADM-02`, §4.3 error catalog.
- `docs-common/design/04. security.md` §5.1–§5.3 (user-type isolation, anti-IDOR `DAR-001`, Maker–Checker).
- `docs-common/design/05. Modules contract.md` §2/§3.1 (dependency direction), §6 `customer.api.CustomerQuery`, §7.1 `account.api.AccountQuery`, §12.1.
- `docs-common/design/01. audit-traceability.md` §5.2/§5.3/§5.6/§5.7 (C2/C3, allowlist, 404 không audit).
- Quyết định đã duyệt: `DEC-CUS-005`, `DEC-CUS-006`, `DEC-ACC-005`, `DEC-ACC-008`, `DEC-ADM-009`, `DEC-ID-003`.

## 2. Ranh giới module (quyết định giữ nguyên)

`GET /api/v1/admin/customers/{customerId}` do **Customer** sở hữu adapter (`CustomerAdminDetailController`), `GET /api/v1/admin/customers/{customerId}/accounts` do **Account** sở hữu adapter (`AdminCustomerAccountsController`). Đây là quyết định đã duyệt (`DEC-CUS-005`, `DEC-ACC-008`; `ACC-DR-11` resolved: Customer không đọc bảng `account`).

Admin **không** tạo controller/DTO/entity thứ hai và **không** đọc `customer`/`account` repository. `ModuleBoundaryArchTest` đã cưỡng chế:

- `adminInboundAdapterMustNotDependOnPersistenceModel`, `adminInternalsMustNotBeConsumedByOtherModules`;
- `customerInternalsMustNotBeConsumedByOtherModules`, `accountInternalsMustNotBeConsumedByOtherModules` (consumer chỉ dùng `customer.api`/`account.api`);
- `customerMustNotReachCrossModuleInternals`, `accountMustNotReachSiblingOrOrchestratorInternals`.

Hệ quả: "Admin tích hợp provider" nghĩa là Admin dùng chung route/authorization contract với provider, còn dữ liệu PII/ownership do provider trả theo `api` contract; không có deep-link entity.

## 3. Provider contracts đã dùng

| Provider | Contract | Hành vi |
|---|---|---|
| Customer | `customer.api.CustomerQuery.findByUserId(long) → Optional<CustomerIdentity>` | mapping `userId → customerId/status`, không PII |
| Customer | **mới** `CustomerQuery.findByCustomerId(long) → Optional<CustomerIdentity>` | exact-ID existence/status cho `API-ADM-02`; bổ sung tối thiểu, cùng snapshot không PII, read-only, `<= 0` → `400 VALIDATION_ERROR`, không tồn tại → empty, `INACTIVE` giữ status thật |
| Customer | `customer.application.CustomerPiiService.getForStaff(long)` (nội bộ Customer) | projection masked `fullName`/phone/email; decrypt chỉ trong Customer |
| Account | `account.api.AccountQuery` (`AccountView`, `AccountViewQuery.forStaff`, `Status`) | read-only masked projection, status thật, không decrypt/blind index |
| Account | `account.application.AccountAccessService.listCustomerAccounts(...)` (nội bộ Account) | use case staff list, re-check permission, existence, masked, `account_id ASC` |

Không có provider method nào bị thay đổi signature ngoài **một method additive** `findByCustomerId` (đóng `ACC-DR-10` cho `404 CUSTOMER_NOT_FOUND`). Không entity/repository nào bị chia sẻ.

## 4. Customer operational lookup (`API-ADM-01`)

- Route: `GET /api/v1/admin/customers/{customerId}`, gate `CUSTOMER_READ`, `Cache-Control: no-store`, `X-Correlation-ID`.
- Response: `CustomerProfile` với `fullName` mask `N*** V*** A***` (`DEC-CUS-005`), `phoneMasked`, `emailMasked`; không trả entity, ciphertext, full PII.
- `customerId` sai schema `ID` → `400 VALIDATION_ERROR` trước persistence.
- Không tồn tại → `404 CUSTOMER_NOT_FOUND`; `INACTIVE` vẫn trả status thật (eligibility `CUS-OI-06` chưa chốt).
- Thiếu permission hoặc identity context không nhất quán (`ROLE_CUSTOMER` cùng permission staff) → `403 AUTH_FORBIDDEN` **trước lookup** + C3 (`SECURITY_ACCESS_DENIED`, entity `CUSTOMER`).

## 5. Account operational lookup (`API-ADM-02`)

- Route: `GET /api/v1/admin/customers/{customerId}/accounts`, gate `ACCOUNT_READ`.
- Response: `AccountsResponse{items[AccountSummary], asOf}`; số tài khoản luôn `**** **** <last4>`; money string scale 2; `status` filter optional, giá trị lạ → `400` trước persistence.
- **Ownership/status**: ownership do Account giữ (`account.customer_id`); status `ACTIVE|LOCKED|CLOSED` đọc thật từ DB; Admin không suy diễn lại rule.
- **Quan hệ customer/account**: list chỉ trả account của `customerId` trong path; customer không tồn tại → `404 CUSTOMER_NOT_FOUND` qua `CustomerQuery.findByCustomerId` (trước đây trả `200 {items: []}` — deviation `ACC-DR-10` đã đóng). Customer tồn tại nhưng chưa có account → `200 {items: [], asOf}`.
- Provider lỗi kỹ thuật → lan truyền thành `5xx` fail-closed; **không** đổi thành empty/404/403 (không che outage, không bypass authorization).

## 6. Cross-scope authorization

| Kịch bản | Kết quả | Cơ chế |
|---|---|---|
| CUSTOMER gọi `API-ADM-01/02` | `403 AUTH_FORBIDDEN` + C3 | chain permission + service re-check permission/role |
| CUSTOMER có `ACCOUNT_READ_SELF` gọi staff list | `403` | chain `ACCOUNT_READ`; service re-check |
| CUSTOMER gọi bằng dữ liệu của customer khác (self-service) | `403` + C3 | `AccountQuery` owner mismatch (`DAR-001`) |
| Staff thiếu permission | `403` trước persistence | chain + service re-check |
| Staff có permission | `200` masked | provider scope theo contract `07` §8.2.4/§8.2.5 |
| Identity snapshot vừa `ROLE_CUSTOMER` vừa permission staff | `403` (fail closed) | `StaffCustomerDetailService`, `AccountAccessService` |
| Resource không tồn tại (đã authorized) | `404` đúng mã | Customer `CUSTOMER_NOT_FOUND`; Account `ACCOUNT_NOT_FOUND` |
| Provider lỗi | `5xx` fail closed | không nuốt/không reclassify |

**Không dùng broad role check thay scope**: mọi path đều kiểm permission cụ thể, actor lấy từ `SessionAuthenticator.Session` server-side; body/header/query không bao giờ là nguồn actor/role/scope.

Data scope staff theo chi nhánh/segment/tenant hiện **chưa có model được duyệt** (`ADM-OI-06`, `ACC-DR-13`, `CUS-OI-05`). Task không tự chế ra scope; hiện trạng được ghi BLOCKED và test khẳng định đúng phần contract đã duyệt (permission + role isolation + anti-IDOR owner). Khi owner chốt model, chỉ cần thêm predicate scope trong provider service, không đổi route/status/mask.

## 7. Audit C3

- Denial ở HTTP chain → `SecurityAccessDeniedAuditor`: một fact `SECURITY_ACCESS_DENIED`/`REJECTED`, `entity_type` theo tài nguyên (`CUSTOMER`/`ACCOUNT`), `actor_user_id` từ session, `correlation_id`/`request_id`, metric `security.audit.events`; không log token/PII.
- Denial ở service khi gọi trực tiếp → `CustomerAuditPort.recordStandalone` (Customer) đã có; Account owner mismatch → `AccountAuditPort.recordStandalone`.
- `401` (thiếu/hết hạn/revoked session) và `404` **không** phát C3 (`design/01` §5.7).
- Truy cập đọc thành công không phát action nghiệp vụ (chỉ technical access log) — tránh write amplification.
- Audit failure best-effort không đổi quyết định `403`; fallback structured security log + metric failure (`SECURITY_AUDIT_WRITE_FAILED`).

## 8. Files thay đổi

| File | Thay đổi |
|---|---|
| `customer/api/CustomerQuery.java` | **+** `findByCustomerId(long)` (additive, PII-free, read-only) |
| `customer/application/CustomerIdentityReader.java` | **+** `findById(long)` |
| `customer/adapter/out/JpaCustomerIdentityReader.java` | **+** implement `findById` qua `JpaRepository.findById` |
| `customer/application/CustomerQueryService.java` | **+** `findByCustomerId` (validate `> 0`, map snapshot, read-only) |
| `customer/application/StaffCustomerDetailService.java` | fail-closed khi snapshot mang `ROLE_CUSTOMER` dù có `CUSTOMER_READ` |
| `account/application/AccountAccessService.java` | staff gate tập trung (`ACCOUNT_READ` + không `ROLE_CUSTOMER`); `404 CUSTOMER_NOT_FOUND` qua `CustomerQuery.findByCustomerId` |
| `src/test/.../CustomerQueryServiceTest.java` | +2 test `findByCustomerId`, fake reader cập nhật, provider failure |
| `src/test/.../CustomerProfileServiceTest.java` | fake reader cập nhật (`findById` không được dùng ở profile path) |
| `src/test/.../CustomerQueryPostgreSqlIntegrationTest.java` | +1 test PG exact-ID + read-only + invalid |
| `src/test/.../StaffCustomerDetailServiceTest.java` | +1 test mixed identity fail-closed |
| `src/test/.../AccountAccessServiceTest.java` | +2 test (unknown customer 404 trước account read; provider failure lan truyền), +`ROLE_CUSTOMER` denial, fake `findByCustomerId` |
| `src/test/.../AdminCustomerAccountsControllerPostgreSqlIntegrationTest.java` | `404 CUSTOMER_NOT_FOUND` thay cho empty page; +1 test customer tồn tại nhưng chưa có account |
| `src/test/.../AdminSecurityMatrixPostgreSqlIntegrationTest.java` | +CUSTOMER cross-scope/C3 assertions cho cả hai route lookup |

Không sửa migration `V1`–`V8`, không thêm bảng/cột, không đổi route/DTO/OpenAPI.

## 9. Test coverage và kết quả

| Hạng mục | Test |
|---|---|
| Masked customer lookup | `CustomerAdminDetailControllerPostgreSqlIntegrationTest` (field set, mask, no PII, no-store) |
| Account ownership/status lookup | `AdminCustomerAccountsControllerPostgreSqlIntegrationTest` (12), `AccountAccessServiceTest` (14) |
| Authorized staff access | cả hai HTTP suite + `AdminSecurityMatrixPostgreSqlIntegrationTest` (AUDITOR `ACCOUNT_READ`) |
| CUSTOMER/cross-scope `403` | `AdminSecurityMatrixPostgreSqlIntegrationTest` (CUSTOMER trên 7 route + C3 metric), account self-read |
| Not-found | customer `404`; account `404 CUSTOMER_NOT_FOUND`; account `404 ACCOUNT_NOT_FOUND` |
| Provider error | `CustomerQueryServiceTest#persistenceFailurePropagatesPerConventions`, `AccountAccessServiceTest#staffListNeverHidesCustomerProviderFailuresAsNotFoundOrEmpty` |
| C3 audit | `SecurityAccessDeniedAuditorTest`, `CustomerAdminDetailControllerPostgreSqlIntegrationTest`, `AdminSecurityMatrixPostgreSqlIntegrationTest` |
| Provider runtime thật | PostgreSQL 16 Testcontainers + Flyway `V1`–`V8` + `CustomerQueryService`/`JpaCustomerIdentityReader`/`CustomerPiiService`/`AccountQueryService` thật |

Lệnh và kết quả (xem §10 để biết lần chạy tổng hợp):

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerQueryPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.account.adapter.in.AdminCustomerAccountsControllerPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.adapter.in.AdminSecurityMatrixPostgreSqlIntegrationTest' \
  --console=plain --max-workers=1
```

→ **BUILD SUCCESSFUL**, 27 test / 0 failure / 0 error / 0 skip
(`CustomerQueryPostgreSqlIntegrationTest` 7, `AdminCustomerAccountsControllerPostgreSqlIntegrationTest` 12, `AdminSecurityMatrixPostgreSqlIntegrationTest` 8).

## 10. Trạng thái và blocker

- `BE-ADM-API-04` **DONE**: masked customer view, account ownership/status, permission/role/anti-IDOR authorization, `403` cross-scope theo contract, `404` đúng contract, C3 audit, provider runtime thật và test PASS.
- **OPEN (không thuộc phạm vi tự quyết):** `ADM-OI-06`/`ACC-DR-13`/`CUS-OI-05` — staff data scope theo chi nhánh/segment/tenant chưa có model. Không claim "unrestricted staff lookup đã đóng".
- **OPEN (chung module):** `ADM-OI-07` durable audit `AuditModuleApi`/`audit_log`; C3 hiện là structured security log + metric theo tiền lệ đã duyệt.
