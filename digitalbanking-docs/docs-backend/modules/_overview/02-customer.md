# Module 02: `customer` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-02`
> **Module:** `com.simulatedbank.banking.customer`
> **Owner chính:** **Đạt** (Phối hợp: Thắng — FE, Phương — `common`)
> **Mốc tập trung:** **Tuần 3 (W3)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-008..011, FR-039), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.2, §11.11), [[digitalbanking-docs/docs-common/design/04. security.md]] (§7)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `customer` quản lý hồ sơ khách hàng cá nhân (`CIF`, họ tên, giấy tờ định danh), trạng thái xác minh danh tính (`kyc_status`), trạng thái hoạt động của hồ sơ khách hàng (`ACTIVE`, `FROZEN`, `CLOSED`) và cưỡng chế các quy tắc bảo vệ dữ liệu định danh cá nhân (PII Masking & Encryption).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `customer` sở hữu độc quyền bảng **`banking.customer`**:

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bảo mật |
|---|---|---|
| `customer_id` | `UUID` / `BIGINT` (`PK`) | Định danh khách hàng duy nhất. |
| `user_id` | `UNIQUE FK -> app_user` | Liên kết 1-1 với tài khoản đăng nhập có `user_type = 'CUSTOMER'`. |
| `cif_number` | `VARCHAR(32) UNIQUE NOT NULL` | Mã định danh khách hàng trên hệ thống ngân hàng (VD: `CIF00000001`). |
| `full_name` | `VARCHAR(150) NOT NULL` | Họ và tên khách hàng (dữ liệu giả lập synthetic theo `NFR-032`). |
| `national_id_enc` | `BYTEA NOT NULL` | Số CCCD/CMND được mã hóa bằng **`AES-256-GCM`** qua `PiiCryptoService`. |
| `national_id_hash` | `CHAR(64) UNIQUE NOT NULL` | **`HMAC-SHA256`** của số CCCD đã chuẩn hóa, dùng để kiểm tra trùng lặp và tìm kiếm chính xác mà không cần giải mã. |
| `national_id_last4` | `CHAR(4) NOT NULL` | 4 chữ số cuối dùng để hiển thị che dấu `********1234`. |
| `kyc_status` | `CHECK IN ('PENDING','VERIFIED','REJECTED')` | Chỉ khách hàng có `kyc_status = 'VERIFIED'` mới được phép khởi tạo chuyển tiền đi. |
| `status` | `CHECK IN ('ACTIVE','FROZEN','CLOSED')` | Nếu `status != 'ACTIVE'`, từ chối mọi giao dịch tài chính. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.2, §11.11)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-CUS-01` | `GET /api/v1/customers/me` | `CUSTOMER` | Trả về hồ sơ cá nhân của chính khách hàng đang đăng nhập. Trường số CCCD luôn trả về dạng che dấu `maskedNationalId: "********1234"`. |
| `API-ADM-01` | `GET /api/v1/admin/customers/{customerId}` | `STAFF` (`CUSTOMER_READ`) | Nhân viên vận hành/rủi ro/quản trị tra cứu thông tin hồ sơ khách hàng phục vụ hỗ trợ và đối soát. Nếu `CUSTOMER` gọi vào endpoint này $\rightarrow$ trả về `403 AUTH_FORBIDDEN` + ghi audit `SECURITY_ACCESS_DENIED`. |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`customer.api`)
- **`CustomerQueryPort`** *(Được gọi bởi `account`, `transfer`, `limit`, `admin`)*:
  - `getCustomerByUserId(userId)` / `getCustomerById(customerId)`: Trả về `CustomerSnapshot` (chứa `customerId`, `userId`, `cifNumber`, `fullName`, `kycStatus`, `status`).
  - `assertCustomerActiveAndKycVerified(customerId)`: Kiểm tra nhanh trạng thái khách hàng trước khi mở tài khoản hoặc chuyển tiền; ném lỗi nghiệp vụ nếu `status != 'ACTIVE'` hoặc `kyc_status != 'VERIFIED'`.

### 4.2. Ports của module khác mà `customer` cần gọi
- **`PiiCryptoService` & `HmacService`** (của module `common`): Mã hóa/giải mã `national_id_enc` và tính `national_id_hash`.
- **`AuditModuleApi`** (của module `audit`): Ghi nhận `SECURITY_ACCESS_DENIED` (`C3`) khi phát hiện truy cập trái phép.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `CustomerEntity`, `CustomerRepository` và logic mã hóa/che dấu PII thông qua module `common`.
2. [ ] Hiện thực `CustomerQueryPort` để `identity` và `account` ánh xạ từ `userId` sang `customerId` trong phiên làm việc.
3. [ ] Hiện thực API `GET /api/v1/customers/me` và API tra cứu dành cho Staff `GET /api/v1/admin/customers/{customerId}`.
4. [ ] Viết test kiểm chứng quy tắc PII Masking (`AC-023`, `CT-API-017`): đảm bảo JSON response và log không bao giờ chứa `national_id_enc`, `national_id_hash` hoặc số CCCD đầy đủ.
