# Module `customer` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-CUSTOMER-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đang phát triển (Skeleton — đã chốt hợp đồng giao tiếp)
> **Owner:** Đạt
> **Phối hợp / Reviewer:** Phương (`common`), Thịnh (`notification`, `audit`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-008..011, FR-039, AC-023, AC-024), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.2.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2.6, §8.2, §8.8), [[digitalbanking-docs/docs-common/design/04. security.md]] (§6, §7), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§6), [[digitalbanking-docs/docs-backend/modules/_overview/02-customer.md]], [[DECISIONS.md]] (`DEC-DOC-001`, `DEC-ID-001`, `DEC-TRF-011`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.customer` quản lý hồ sơ khách hàng cá nhân (`CIF`, họ tên, số CCCD/CMND, số điện thoại, địa chỉ email), trạng thái xác minh danh tính (`kyc_status`), trạng thái hoạt động (`ACTIVE`, `LOCKED`, `CLOSED`) và chịu trách nhiệm mã hóa ở tầng ứng dụng (`AES-256-GCM`), tạo chỉ mục mù (`HMAC-SHA256`) cũng như che dấu dữ liệu định danh cá nhân (PII) trước khi trả ra REST API hoặc cung cấp cho các module khác.

---

## 2. Bảng Dữ liệu Sở hữu Độc quyền (`Write-Owner`)

Module `customer` là chủ sở hữu ghi duy nhất của bảng **`banking.customer`** (`04. Database design & analysis.md` §4.2.2, migration `V6` theo `DEC-COM-001`):

| Cột | Kiểu dữ liệu & Ràng buộc | Quy tắc Nghiệp vụ & Bảo mật |
|---|---|---|
| `customer_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh nội bộ duy nhất của khách hàng. |
| `user_id` | `BIGINT UNIQUE NOT NULL FK -> banking.app_user` | Liên kết 1-1 với tài khoản đăng nhập có `user_type = 'CUSTOMER'`. |
| `cif_no` | `VARCHAR(32) UNIQUE NOT NULL` | Mã khách hàng trên hệ thống ngân hàng (ví dụ `CIF00000001`). |
| `full_name_enc` | `BYTEA NOT NULL` | Họ và tên khách hàng mã hóa `AES-256-GCM` qua `PiiCryptoService`. |
| `national_id_enc` | `BYTEA NOT NULL` | Số CCCD/CMND mã hóa `AES-256-GCM`. |
| `national_id_hash` | `CHAR(64) UNIQUE NOT NULL` | Chỉ mục mù `HMAC-SHA256` phục vụ kiểm tra trùng lặp và tra cứu chính xác. |
| `national_id_last4` | `CHAR(4) NOT NULL` | 4 chữ số cuối để hiển thị che dấu `********1234`. |
| `phone_enc`, `phone_hash`, `phone_last3` | `BYTEA`, `CHAR(64)`, `CHAR(3)` | Số điện thoại mã hóa `AES-256-GCM`, chỉ mục mù `HMAC-SHA256` và 3 số cuối để hiển thị `********123`. |
| `email_enc`, `email_hash`, `email_masked` | `BYTEA`, `CHAR(64)`, `VARCHAR(128)` | Email mã hóa `AES-256-GCM`, chỉ mục mù `HMAC-SHA256` và chuỗi che dấu `n***@example.com`. |
| `kyc_status` | `VARCHAR(16) NOT NULL CHECK IN ('PENDING','VERIFIED','REJECTED')` | Trạng thái KYC; chỉ khách hàng `VERIFIED` mới được khởi tạo chuyển tiền đi. |
| `status` | `VARCHAR(16) NOT NULL CHECK IN ('ACTIVE','LOCKED','CLOSED')` | Trạng thái hoạt động của hồ sơ khách hàng (`04` §4.2.2). |
| `created_at`, `updated_at` | `TIMESTAMPTZ NOT NULL` | Thời điểm tạo và cập nhật (UTC). |

---

## 3. Hợp đồng Giao tiếp Nội bộ (`design/05` §6, `DEC-TRF-011`)

### 3.1. Public Ports do `customer` cung cấp (`com.simulatedbank.banking.customer.api`)

| Port & Phương thức | Consumer chính | Giao dịch (`TX`) | Hợp đồng Đầu vào / Đầu ra & Bất biến |
|---|---|---|---|
| `CustomerQuery.findByUserId(userId)` | `identity`, `account` | `R` (Read-only) | Trả về `Optional<CustomerIdentity(customerId, userId, status, kycStatus)>` để ánh xạ từ `userId` đã xác thực sang `customerId` mà không giải mã PII. |
| `CustomerQuery.getCustomerView(customerId, actor)` | `account`, `admin` | `R` (Read-only) | Trả về thông tin hồ sơ khách hàng đã che dấu PII theo đúng quyền và phạm vi truy cập. |
| `CustomerQuery.resolveNotificationDestination(customerId, channel, systemActor)` | `notification` (`T3`), `identity` (OTP post-commit) | `R` (Read-only, ngoài TX tiền) | Giải mã `phone_enc` hoặc `email_enc` trong bộ nhớ tạm thời ngay trước khi gửi OTP/thông báo (`DEC-TRF-011`). Tuyệt đối không lưu bản rõ vào bảng `notification` hoặc ghi ra log. |

### 3.2. Chiến lược Kiểm thử Độc lập cho Consumer (`AGENTS.md`)

Trong thời gian module `customer` đang ở trạng thái Skeleton (`src/main/java/com/simulatedbank/banking/customer/**/.gitkeep`), các module phụ thuộc (`identity`, `account`, `notification`, `admin`) tuân thủ quy tắc tại `AGENTS.md`:
- Sử dụng test double / fake chỉ trong `src/test` theo đúng hợp đồng `CustomerQuery` tại `05. Modules contract.md` §6.
- Không truy cập trực tiếp bảng `banking.customer` từ module khác và không đăng ký fake bean trong `src/main`.

---

## 4. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.2, §8.8)

- `API-CUS-01` — `GET /api/v1/customers/me`: Khách hàng (`CUSTOMER`, `CUSTOMER_READ_SELF`) xem hồ sơ cá nhân của chính mình với các trường nhạy cảm đã được che dấu (`maskedNationalId`, `maskedPhone`, `maskedEmail`).
- `API-ADM-01` — `GET /api/v1/admin/customers/{customerId}`: Nhân viên (`OPERATIONS`, `ADMIN`, `AUDITOR` có quyền `CUSTOMER_READ` theo `DEC-ID-001`) tra cứu hồ sơ khách hàng phục vụ vận hành/kiểm toán; từ chối `CUSTOMER` với `403 AUTH_FORBIDDEN` + ghi audit `SECURITY_ACCESS_DENIED` (`C3`).

---

## 5. Checklist Công việc Triển khai (`Tasks`)

- [ ] **BE-CUS-01 (P0 — Migration & Domain/Persistence):** Tạo bảng `banking.customer` trong migration `V6__create_customer_and_account_tables.sql` (`DEC-COM-001`), hiện thực `CustomerEntity`, `CustomerJpaRepository` và tích hợp mã hóa/blind index (`PiiCryptoService`, `HmacService`, `PiiMaskingUtils`).
- [ ] **BE-CUS-02 (P0 — Internal Port `CustomerQuery`):** Hiện thực `findByUserId`, `getCustomerView` và `resolveNotificationDestination` (`DEC-TRF-011`).
- [ ] **BE-CUS-03 (P0 — REST Endpoints & Masking):** Hiện thực `CustomerController` cho `API-CUS-01` (`GET /api/v1/customers/me`) và `API-ADM-01` (`GET /api/v1/admin/customers/{customerId}`).
- [ ] **BE-CUS-04 (P0 — Kiểm thử Tích hợp PostgreSQL & Bảo mật PII):** Kiểm chứng `AC-023`, `AC-024`, `CT-API-017` trên PostgreSQL 16 Testcontainers (đảm bảo response/log không bao giờ lộ `*_enc`, `*_hash` hay CCCD/SĐT/email bản rõ, và đúng định dạng `n***@example.com` của `PiiMaskingUtils.maskEmail`).
