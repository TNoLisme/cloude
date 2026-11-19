# Customer module — Thiết kế triển khai (BE-CUS-DOC-01)

> Baseline: `TNoLisme/cloude`, nhánh `dev2`, commit `56210cc80ad742ddad9139dc1a08fe9ff524465a` (16/11/2026). **DRAFT — planning/documentation only.** Không chứng minh đã implement.

## 1. Căn cứ và quy tắc nguồn

Nguồn: `digitalbanking-docs/docs-common/03. SRS.md` (FR-008–011, FR-039, FR-049); `04. Database design & analysis.md` (§4.2.2); `07. API contracts.md` (§7.2.9, §8.2.1, §8.2.4); `design/05. Modules contract.md` (§6); `docs-backend/00. architecture & conventions.md`; `docs-backend/modules/_overview/02-customer.md`; `openapi/digital-banking-v1.yaml`. Ưu tiên tài liệu `docs-common/01–11` trước các design/overview. Các API/Java interface đề xuất phải phân biệt với API được chốt.

## 2. Phạm vi và trách nhiệm

Customer là write-owner của `banking.customer`: ánh xạ duy nhất `user_id -> customer_id`, quản lý PII hồ sơ và trạng thái `ACTIVE`/`INACTIVE`, cung cấp hồ sơ đã mask cho chủ sở hữu/staff và snapshot tối thiểu cho Identity, Account, Admin. Identity sở hữu `app_user`, session, authentication, roles; Account sở hữu account và balance/ownership; Transfer sở hữu money path/idempotency transfer; Audit sở hữu audit log; Admin sở hữu quyền nhân viên và approval. Không truy cập trực tiếp bảng/implementation của module khác.

| Requirement | Phạm vi Customer | Điều kiện đạt |
|---|---|---|
| FR-008, UC-004, AC-005 | GET profile của user hiện tại | Response đúng `CustomerProfile`, không đọc chéo customer |
| FR-039, AC-023, CT-API-017 | PII encryption/masking | Không rò raw phone/email hoặc ciphertext/key trong response/log |
| FR-049, AC-027 | Staff lookup | `CUSTOMER_READ` + scope, deny/audit khi sai quyền |
| FR-011, AC-024 | Hỗ trợ Account ownership | CustomerQuery tối thiểu, Account tự enforce ownership |

**Ngoài phạm vi v1 theo nguồn chuẩn:** POST tạo customer từ client, update/delete customer, full PII disclosure, KYC, CIF, national ID, tìm kiếm staff. Đây là các đề xuất/điểm mở, không được tự thêm API, bảng hoặc status.

## 3. Hiện trạng mã nguồn dev2

- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/customer/{api,application,domain,adapter/in,adapter/out}` chỉ có `.gitkeep`; chưa thấy source Customer triển khai.
- `digitalbanking-backend/src/main/resources/db/migration/V1__init_schema.sql` chỉ có `CREATE SCHEMA IF NOT EXISTS banking;`, chưa có bảng Customer.
- `docs-backend/modules/customer/README.md` ban đầu chỉ có `# customer`.
- Common đã có `PiiCryptoService`, `PiiMaskingUtils`, security/context/error plumbing và `SessionAuthenticator` SPI; **tái sử dụng, không implement lại**. Identity authentication binding thực tế và Audit integration chưa được xác nhận tại baseline.
- Không đánh dấu implementation DONE chỉ dựa trên thiết kế hoặc Common component đã tồn tại.

## 4. Database và persistence

Theo `04` §4.2.2, bảng `banking.customer` có:

| Cột | Kiểu | Ràng buộc |
|---|---|---|
| `customer_id` | BIGINT | IDENTITY, PK |
| `user_id` | BIGINT | NOT NULL, UNIQUE, FK `banking.app_user` |
| `full_name_enc` | BYTEA | NOT NULL |
| `phone_enc`, `email_enc` | BYTEA | NULLABLE |
| `status` | VARCHAR(16) | NOT NULL DEFAULT `ACTIVE`, CHECK `ACTIVE/INACTIVE` |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() |

Index `uq_customer_user_id`, `ix_customer_status`. Không hard delete, không cascade; Flyway forward-only, không sửa V1. Migration phải được đánh số phối hợp với Identity vì FK tới `app_user`. App-layer AES-256-GCM dùng Common primitive, không encrypt trong SQL; ciphertext không dùng để tìm kiếm chính xác hay unique. Không tự bổ sung national ID, CIF, KYC columns hoặc blind indexes. Persistence theo Spring Data JPA/Hibernate trong backend conventions; JDBC trên sơ đồ kiến trúc chỉ là lớp kết nối tới PostgreSQL, không có quy định cấm JPA. Entity không được expose trực tiếp qua REST hoặc module public API.

## 5. Nội bộ và dependencies

| Provider / consumer | Interface/requirement | Gate |
|---|---|---|
| Identity | Principal `userId` tin cậy; customer user type; app_user row tồn tại | Session authenticator, migration của Identity |
| Account | `customer.api.CustomerQuery` trả minimal ID/status snapshot | Không import entity/repository Customer; Account tự kiểm quyền sở hữu |
| Admin | Masked customer view, permission và data scope | Chốt scope và rule masking fullName |
| Common | PiiCryptoService, PiiMaskingUtils, error mapping, RequestContext, security filter | Crypto config và integration tests |
| Audit | SECURITY_ACCESS_DENIED khi truy cập trái phép | Chốt audit service API và failure semantics |
| DB owner | Tên migration và FK app_user | Tránh đụng Flyway version |

Interface đề xuất: `CustomerQuery.findByUserId(userId) -> Optional<CustomerIdentity(customerId,userId,status)>`; `CustomerQuery.getCustomerView(customerId, actorContext) -> masked CustomerView`. Public API module chỉ dùng immutable DTO, không trả JPA entity hoặc raw PII. Dependency hướng consumer → provider `api`; không tạo chu trình customer ↔ identity.

## 6. REST contract

| Endpoint | Role | Trả về |
|---|---|---|
| `GET /api/v1/customers/me` (`API-CUS-01`) | CUSTOMER self, từ principal | 200 `CustomerProfile`, 401/403/404/5xx |
| `GET /api/v1/admin/customers/{customerId}` (`API-ADM-01`) | OPERATIONS/ADMIN/AUDITOR có CUSTOMER_READ và scope | 200 masked `CustomerProfile`, 400/401/403/404/5xx |
| `GET /api/v1/admin/customers/{customerId}/accounts` (`API-ADM-02`) | ACCOUNT_READ, staff scope | Account/ops composition sở hữu; Customer chỉ cung cấp lookup |

CustomerProfile: `customerId`, `userId`, `fullName`, `phoneMasked?`, `emailMasked?`, `status`, `createdAt`, `updatedAt`. ID là decimal string, timestamps Instant UTC, `X-Correlation-ID`, `Cache-Control: no-store`. Self profile hiển thị `fullName` đầy đủ, phone/email được mask. Staff fullName phải mask theo `07` §8.2.4; thuật toán mask chi tiết cần quyết định để viết test ổn định. Không tự thêm success envelope, PII hoặc field KYC. Lỗi dùng RFC 9457 Problem của Common.

> **Cập nhật 17/11/2026 (BE-CUS-IMP-06):** API-ADM-01 đã triển khai — `customerId` theo schema `ID` `[1-9][0-9]{0,18}` (sai → 400 trước lookup), permission `CUSTOMER_READ` ở chain + service re-check, thiếu quyền → 403 + audit `SECURITY_ACCESS_DENIED` C3 trước lookup, row vắng → 404 không audit, INACTIVE vẫn 200. Thuật toán mask `fullName` tạm thời theo [DEC-CUS-005](../../../../../DECISIONS.md) (`PiiMaskingUtils.maskFullName`: token ≥2 giữ ký tự đầu + `***`, token 1 ký tự che toàn bộ) — CUS-OI-02 chờ owner chốt oracle. Data-scope staff vẫn BLOCKED (CUS-OI-05). Chi tiết: [BE-CUS-IMP-06.md](BE-CUS-IMP-06.md), Modules contract §6.3.

> **Cập nhật 17/11/2026 (BE-CUS-IMP-07):** Identity principal/session/RBAC đã được kiểm chứng
> end-to-end qua HTTP + PostgreSQL thật; self binding dùng `CustomerQuery.findByUserId`, disabled
> identity fail-closed và metadata role/permission client không thể bypass chain. C3 deny audit có
> actor/target/outcome/correlation/request ID và failure không đổi 403; ArchUnit cấm Customer import
> internals của Identity/Account/Admin/Audit. Real Account ownership/status vẫn BLOCKED vì module
> Account chỉ có skeleton; Admin data-scope CUS-OI-05 và durable Audit `AuditModuleApi`/`audit_log`
> cũng chưa có. Không coi adapter test/log metric là real provider integration. Chi tiết:
> [BE-CUS-IMP-07.md](BE-CUS-IMP-07.md), Modules contract §6.4, DEC-CUS-006.

## 7. Workflow, validation, control, idempotency và failure

**Self:** HTTP request → Identity session authentication → role CUSTOMER → principal userId → CustomerQuery load row theo userId → decrypt các field cần hiển thị → mask phone/email → immutable DTO → HTTP 200. Không nhận customerId/userId từ body/query của người dùng để xác định chủ thể.

**Staff:** session staff → CUSTOMER_READ → data-scope check → validate path decimal ID → tìm customer → decrypt trong phạm vi được phép → mask mọi field theo policy staff → 200. Thiếu quyền/scope: 403 + `SECURITY_ACCESS_DENIED`; row không tồn tại: 404. Cần chống enumeration và kiểm soát quyền ở service, không chỉ security filter.

**Provisioning chưa được chốt:** Hiện `07` không có Customer create API. Nếu owner chấp thuận luồng internal create: kiểm app_user loại CUSTOMER → validate/normalize PII theo policy → encrypt → insert với unique user_id → ghi audit bắt buộc → commit. Duplicate/concurrent create cùng user_id chỉ được một row nhờ DB unique constraint; chính sách replay/conflict phải được thống nhất trước implementation. Không tạo thêm idempotency table, không dùng transfer idempotency record.

**Validation:** `customerId/userId` hợp lệ, >0; user_type CUSTOMER; status đúng DB enum; nullable phone/email; full name required; size/format chi tiết theo source/owner xác nhận. ACTIVE không đồng nghĩa KYC VERIFIED. Read GET là idempotent tự nhiên, không cần idempotency key; write chưa có contract.

**Failure:** 401 khi session invalid/expired; 403 khi role/scope không đủ (security audit); 400 khi ID sai; 404 khi customer thiếu; 5xx khi DB/crypto/key lỗi, fail closed, không trả response thiếu mask/partial PII. Không log raw values, ciphertext, token hoặc encryption keys. Khi sau này có mutation cần audit atomic, audit fail phải rollback mutation theo policy được duyệt. Retry GET an toàn, retry write không được mở trước khi chốt idempotency.

## 8. Testing và acceptance

- Unit: status mapping, missing row, self/staff projection, encryption/decryption, mask phone/email/fullName theo policy, crypto errors.
- HTTP/MockMvc: 200 self/staff, 401 anonymous, 403 CUSTOMER vào admin, 403 staff thiếu permission/out of scope + audit, 404 missing, 400 bad ID; no-store, correlation, Problem format.
- PostgreSQL Testcontainers: migrate fresh, FK app_user, unique user_id, NOT NULL fullName_enc, CHECK status, nullable encrypted fields, duplicate concurrent insert, rollback, at-rest ciphertext.
- Integration: Identity principal thật, Account API contract, Common crypto, Audit denied event, ArchUnit no forbidden cross-module import.
- Security: AC-023/024/027, CT-API-015–017, T-AUD-16; scan payload/error/log để đảm bảo không có PII/crypto key leakage. Test data hoàn toàn synthetic.
- Evidence: commit SHA, test commands, test results, schema checks, open blockers; không dùng mock để nhận `DONE` cho full integration.

## 9. Mâu thuẫn và open issues

| ID | Vấn đề | Quyết định/chủ sở hữu |
|---|---|---|
| CUS-OI-01 | Overview 02-customer mô tả national_id, CIF, KYC, `FROZEN/CLOSED`; `04` §4.2.2 chỉ có encrypted name/phone/email và `ACTIVE/INACTIVE` | BA/DB/API/Security chốt và sửa docs-common trước khi mở rộng DB |
| CUS-OI-02 | Staff view yêu cầu mask `fullName`, chưa rõ quy tắc mask chuẩn | API/BA/Security chốt oracle |
| CUS-OI-03 | FE mô tả OPS-CREATE-CUSTOMER nhưng API chuẩn chưa có create; OI-API-08 search chưa chốt | Admin/BA/API chốt scope và contract |
| CUS-OI-04 | Customer provisioning, migration FK, Identity session mapping chưa có implementation | Identity/DB owner chốt giao diện, thứ tự migration |
| CUS-OI-05 | Data scope staff và audit deny policy chưa có runtime integration xác nhận | Admin/Audit/Security |
| CUS-OI-06 | POST Transfer với key mới trả 403 khi Customer INACTIVE theo DEC-INT-013. Các mutation và cascade khác vẫn chờ chính sách. | BA/Account/Transfer |

## 10. Implementation gates

Gate A: chốt source conflicts và dependency API/DDL. Gate B: migration → entity/repository → crypto/query ports. Gate C: self REST → staff REST. Gate D: integration và security/regression evidence. Backlog P2 không nằm trên critical path khi chưa được duyệt. Xem [tasks/README.md](tasks/README.md).
