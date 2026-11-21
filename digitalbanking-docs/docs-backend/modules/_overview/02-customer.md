# Module 02: `customer` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-02`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.customer`
> **Owner chính:** **Đạt** (Phối hợp: Thắng — FE, Phương — `common`, Thịnh — `notification` & `audit`)
> **Mốc tập trung:** **Tuần 3 (W3)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-008..011, FR-039), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.2.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.2, §8.8), [[digitalbanking-docs/docs-common/design/04. security.md]] (§6, §7), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§6), [[DECISIONS.md]] (`DEC-DOC-001`, `DEC-TRF-011`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `customer` quản lý hồ sơ khách hàng cá nhân (`CIF`, họ tên, giấy tờ định danh, số điện thoại, email), trạng thái xác minh danh tính (`kyc_status`), trạng thái hoạt động của hồ sơ khách hàng (`ACTIVE`, `LOCKED`, `CLOSED`) và cưỡng chế các quy tắc bảo vệ dữ liệu định danh cá nhân (mã hóa `AES-256-GCM`, chỉ mục mù `HMAC-SHA256` và che dấu PII khi hiển thị).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `customer` sở hữu độc quyền bảng **`banking.customer`** (`04. Database design & analysis.md` §4.2.2, migration `V6`):

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bảo mật |
|---|---|---|
| `customer_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh nội bộ duy nhất của khách hàng. |
| `user_id` | `BIGINT UNIQUE NOT NULL FK -> app_user` | Liên kết 1-1 với tài khoản đăng nhập có `user_type = 'CUSTOMER'`. |
| `cif_no` | `VARCHAR(32) UNIQUE NOT NULL` | Mã định danh khách hàng trên hệ thống ngân hàng (VD: `CIF00000001`). |
| `full_name_enc` | `BYTEA NOT NULL` | Họ và tên khách hàng được mã hóa bằng **`AES-256-GCM`** qua `PiiCryptoService` (dữ liệu synthetic theo `NFR-032`). |
| `national_id_enc` | `BYTEA NOT NULL` | Số CCCD/CMND được mã hóa bằng **`AES-256-GCM`** qua `PiiCryptoService`. |
| `national_id_hash` | `CHAR(64) UNIQUE NOT NULL` | Chỉ mục mù **`HMAC-SHA256`** của số CCCD đã chuẩn hóa, dùng để kiểm tra trùng lặp và tra cứu chính xác mà không cần giải mã. |
| `national_id_last4` | `CHAR(4) NOT NULL` | 4 chữ số cuối dùng để hiển thị che dấu `********1234`. |
| `phone_enc`, `phone_hash`, `phone_last3` | `BYTEA`, `CHAR(64)`, `CHAR(3)` | Số điện thoại mã hóa `AES-256-GCM`, chỉ mục mù `HMAC-SHA256` và 3 số cuối để hiển thị che dấu `********123`. |
| `email_enc`, `email_hash`, `email_masked` | `BYTEA`, `CHAR(64)`, `VARCHAR(128)` | Địa chỉ email mã hóa `AES-256-GCM`, chỉ mục mù `HMAC-SHA256` và chuỗi che dấu `n***@example.com`. |
| `kyc_status` | `VARCHAR(16) CHECK IN ('PENDING','VERIFIED','REJECTED')` | Chỉ khách hàng có `kyc_status = 'VERIFIED'` mới được phép thực hiện giao dịch chuyển tiền đi. |
| `status` | `VARCHAR(16) CHECK IN ('ACTIVE','LOCKED','CLOSED')` | Trạng thái hồ sơ khách hàng (`04` §4.2.2). Nếu `status != 'ACTIVE'`, từ chối mọi giao dịch tài chính. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.2, §8.8)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-CUS-01` | `GET /api/v1/customers/me` | `CUSTOMER` (`CUSTOMER_READ_SELF`) | Trả về hồ sơ cá nhân của chính khách hàng đang đăng nhập. Các trường nhạy cảm luôn được che dấu theo chuẩn `07` §2.6 (`maskedNationalId: "********1234"`, `maskedPhone: "********123"`, `maskedEmail: "n***@example.com"`). |
| `API-ADM-01` | `GET /api/v1/admin/customers/{customerId}` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`CUSTOMER_READ`) | Nhân viên vận hành/quản trị/kiểm toán tra cứu thông tin hồ sơ khách hàng đã che dấu PII phục vụ hỗ trợ và đối soát (`DEC-ID-001`). Nếu `CUSTOMER` gọi vào endpoint này $\rightarrow$ trả về `403 AUTH_FORBIDDEN` + ghi audit `SECURITY_ACCESS_DENIED` (`C3`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §6)

### 4.1. Ports cung cấp cho các module khác gọi (`customer.api.CustomerQuery`)
- **`findByUserId(long userId) -> Optional<CustomerIdentity>`** *(Được gọi bởi `identity`, `account`)*:
  - Trả về snapshot tối thiểu `CustomerIdentity(customerId, userId, status, kycStatus)` mà không giải mã PII.
- **`getCustomerView(long customerId, ActorContext actor) -> CustomerView`** *(Được gọi bởi `account`, `admin`)*:
  - Trả về hồ sơ khách hàng đã che dấu PII theo đúng quyền và phạm vi truy cập.
- **`resolveNotificationDestination(long customerId, NotificationChannel channel, SystemActorContext systemActor) -> Optional<ResolvedDestination>`** *(Được gọi bởi `notification` ở pha `T3` và `identity` khi gửi OTP sau khi commit — `DEC-TRF-011`)*:
  - Giải mã `phone_enc` hoặc `email_enc` trong bộ nhớ tạm thời ngay tại thời điểm gửi thông báo/OTP; tuyệt đối không lưu bản rõ vào bảng `notification` hoặc ghi ra log.

### 4.2. Ports của module khác mà `customer` cần gọi
- **`PiiCryptoService`, `HmacService`, `PiiMaskingUtils`** (của module `common`): Mã hóa/giải mã `*_enc`, tính blind index `*_hash` và định dạng chuỗi che dấu.
- **`AuditModuleApi`** (của module `audit`): Ghi nhận `SECURITY_ACCESS_DENIED` (`C3`) khi phát hiện truy cập trái phép.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực migration `V6` (phần bảng `banking.customer`), `CustomerEntity`, `CustomerRepository` và logic mã hóa/che dấu PII (`full_name_enc`, `national_id_enc`, `phone_enc`, `email_enc`) thông qua module `common`.
2. [ ] Hiện thực `customer.api.CustomerQuery` (`findByUserId`, `getCustomerView`, `resolveNotificationDestination`) để `identity`, `account`, `notification` và `admin` tích hợp đúng hợp đồng `design/05` §6.
3. [ ] Hiện thực API `GET /api/v1/customers/me` (`API-CUS-01`) và API tra cứu dành cho Staff `GET /api/v1/admin/customers/{customerId}` (`API-ADM-01`).
4. [ ] Viết bộ kiểm thử tích hợp PostgreSQL kiểm chứng quy tắc PII Masking (`AC-023`, `CT-API-017`) và phân quyền chống IDOR (`AC-024`): đảm bảo JSON response và log không bao giờ chứa `*_enc`, `*_hash` hoặc số CCCD/SĐT/email bản rõ.
