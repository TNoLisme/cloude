# Database 03. Flyway Migration, Roles & Seed Strategy

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DB-03`
> **Phiên bản:** 1.0
> **Ngày:** 07/11/2026
> **Trạng thái:** Hoàn thành
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]], [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]]

---

## 1. Quy ước Quản lý Flyway Migration

| Hạng mục | Quy định |
|---|---|
| **Thư mục chứa script** | `src/main/resources/db/migration/` |
| **Schema mặc định** | `spring.flyway.schemas: banking`, `spring.flyway.default-schema: banking` |
| **Hibernate DDL mode** | `spring.jpa.hibernate.ddl-auto: validate` (Bắt buộc trên mọi profile `local`, `test`, `demo`) |
| **Đặt tên Versioned Migration** | `V<SỐ>__<mô_tả_snake_case>.sql` (hai dấu gạch dưới `__`), ví dụ: `V1__init_schema_and_identity.sql` |
| **Quy tắc bất biến** | **Tuyệt đối không sửa** file `V<n>__*.sql` đã merge vào `main`. Mọi thay đổi schema phải tạo file `V<n+1>__*.sql` mới |

### Thứ tự Phân tách Migration Chuẩn (Recommended Baseline)

Khi dựng từ đầu hoặc kiểm tra bộ migration của dự án, các bảng phải được tạo theo đúng thứ tự phụ thuộc khóa ngoại (`FK`) sau:

1. **`V1__init_schema_and_identity.sql`**:
   - `CREATE SCHEMA IF NOT EXISTS banking;`
   - Bảng `app_user`, `role`, `permission`, `user_role`, `role_permission`, `user_privilege`, `device`, `session`, `auth_challenge`, `auth_attempt`, `password_history` + toàn bộ Indexes/Partial Indexes của nhóm Identity.
2. **`V2__customer_and_account.sql`**:
   - Bảng `customer`, `account` + Indexes (`uq_account_no_hash`, `ix_account_customer`,...).
3. **`V3__transfer_ledger_and_idempotency.sql`**:
   - Bảng `transfer`, `ledger_entry`, `idempotency_record` + Trigger `trg_ledger_entry_immutable`, `trg_transfer_terminal_immutable`.
4. **`V4__limit_and_approval.sql`**:
   - Bảng `approval_request`, `limit_config`, `limit_usage` + Partial Unique Indexes (`uq_limit_active_*`, `uq_limit_usage_*`).
5. **`V5__audit_and_retention.sql`**:
   - Bảng `audit_log`, `legal_hold`, `retention_policy` + Trigger `trg_audit_log_immutable` chặn `UPDATE`/`DELETE`.
6. **`V6__fraud_and_notification.sql`**:
   - Bảng `fraud_rule_config`, `fraud_flag`, `notification` + Partial Unique Index `uq_fraud_rule_active`.
7. **`V7__seed_reference_data.sql`**:
   - Seed dữ liệu danh mục bắt buộc (Roles, Permissions, Fraud Rules mặc định, Retention Policies mặc định).

---

## 2. Phân tách Quyền Database Roles (Least Privilege)

Theo [[digitalbanking-docs/docs-common/04. Database design & analysis.md#9.2. Cơ chế bảo vệ]] và [[digitalbanking-docs/docs-common/design/01. audit-traceability.md#6.4. Quyền DB và trigger bất biến]], hệ thống định nghĩa 4 nhóm quyền DB:

| DB Role | Mục đích sử dụng | Quyền hạn trên schema `banking` |
|---|---|---|
| `dbank_migration` | Dùng cho Flyway khi khởi động ứng dụng | `ALL PRIVILEGES` (DDL: `CREATE`, `ALTER`, `TRIGGER` + DML) |
| `dbank_app_rw` | Dùng cho Spring Boot DataSource chính | `SELECT, INSERT, UPDATE, DELETE` trên các bảng nghiệp vụ; **chỉ `SELECT, INSERT` (không `UPDATE, DELETE`)** trên `audit_log` và `ledger_entry` |
| `dbank_audit_writer` | *(Tùy chọn tách riêng hoặc gộp trong `dbank_app_rw` ở MVP)* | `SELECT, INSERT` trên `audit_log` |
| `dbank_auditor_ro` | Dùng cho job Verification / truy vấn đối soát độc lập | Chỉ `SELECT` trên `audit_log`, `transfer`, `ledger_entry`, `account` |

```sql
-- Câu lệnh thu hồi quyền sửa/xóa trên bảng append-only đối với user ứng dụng:
REVOKE UPDATE, DELETE, TRUNCATE ON banking.audit_log FROM dbank_app_rw;
REVOKE UPDATE, DELETE, TRUNCATE ON banking.ledger_entry FROM dbank_app_rw;
```

---

## 3. Dữ liệu Seed Danh mục Chuẩn (`V7__seed_reference_data.sql`)

### 3.1. Seed 4 Roles & 15 Permissions Chuẩn

| `role_code` | Tên hiển thị | Danh sách `permission_code` được gán |
|---|---|---|
| `CUSTOMER` | Khách hàng cá nhân | `ACCOUNT_READ_SELF`, `TRANSACTION_READ_SELF`, `TRANSFER_CREATE`, `LIMIT_READ_SELF` |
| `OPERATIONS` | Nhân viên vận hành | `CUSTOMER_READ`, `ACCOUNT_READ`, `TRANSACTION_READ`, `AUDIT_READ`, `FRAUD_REVIEW`, `LIMIT_MAKER` |
| `ADMIN` | Quản trị viên hệ thống | `CUSTOMER_READ`, `ACCOUNT_READ`, `TRANSACTION_READ`, `AUDIT_READ`, `ROLE_MANAGE`, `LIMIT_MANAGE`, `RETENTION_MANAGE`, `FRAUD_READ` |
| `AUDITOR` | Kiểm toán viên độc lập | `AUDIT_READ`, `AUDIT_EXPORT`, `TRANSACTION_READ`, `ACCOUNT_READ` |

> [!NOTE]
> Quyền duyệt yêu cầu Maker-Checker (`CHECKER`) không nằm trong bảng `permission` mà được cấp qua bảng `banking.user_privilege (user_id, privilege_code = 'CHECKER', status = 'ACTIVE')` cho các tài khoản Staff (Operations Checker / Admin Checker).

### 3.2. Seed Cấu hình Luật Gian lận Mặc định (`banking.fraud_rule_config`)

| `rule_code` | `version` | `rule_type` | `threshold_amount` | `threshold_count` | `window_seconds` | `severity` | `enabled` |
|---|---|---|---|---|---|---|---|
| `LARGE_TRANSFER` | `1` | `LARGE_VALUE` | `50000000.00` | `NULL` | `NULL` | `HIGH` | `TRUE` |
| `HIGH_FREQUENCY_10M` | `1` | `HIGH_FREQUENCY` | `NULL` | `5` | `600` | `MEDIUM` | `TRUE` |
| `UNUSUAL_RAPID_DRAIN` | `1` | `UNUSUAL_PATTERN` | `20000000.00` | `3` | `300` | `HIGH` | `TRUE` |

### 3.3. Seed Chính sách Lưu trữ Mặc định (`banking.retention_policy`)

| `data_class` | `retention_days` | `archive_after_days` | `purge_after_days` | `legal_hold_supported` |
|---|---|---|---|---|
| `TRANSFER` | `1825` (5 năm) | `1460` (4 năm) | `1825` | `TRUE` |
| `LEDGER_ENTRY` | `1825` (5 năm) | `1460` (4 năm) | `1825` | `TRUE` |
| `AUDIT_LOG` | `1825` (5 năm) | `1460` (4 năm) | `1825` | `TRUE` |
| `FRAUD_FLAG` | `1825` (5 năm) | `1460` (4 năm) | `1825` | `TRUE` |
| `LIMIT_HISTORY` | `1825` (5 năm) | `1460` (4 năm) | `1825` | `TRUE` |
| `AUTH_CHALLENGE` | `90` (90 ngày) | `NULL` | `90` | `FALSE` |
| `NOTIFICATION` | `90` (90 ngày) | `NULL` | `90` | `FALSE` |
| `SESSION` | `30` (30 ngày) | `NULL` | `30` | `FALSE` |
| `IDEMPOTENCY_RECORD` | `1` (24 giờ) | `NULL` | `1` | `FALSE` |

---

## 4. Dữ liệu Seed Demo / Test (`profile: local, demo`)

> [!CAUTION]
> Theo `NFR-032`, **tuyệt đối không sử dụng dữ liệu PII thật** (tên thật kèm CCCD/SĐT/STK thật). Dữ liệu demo chỉ được nạp khi bật Spring profile `local` hoặc `demo` (thông qua `ApplicationRunner` hoặc thư mục `db/seed-demo`).

Bộ tài khoản mẫu tối thiểu phục vụ Demo (D1–D5) và E2E Test:

| Username | Loại (`user_type`) | Role / Privilege | Tài khoản & Số dư khởi tạo (`VND`) | Mục đích Demo / Test |
|---|---|---|---|---|
| `cust_nguyen_a` | `CUSTOMER` | `CUSTOMER` | • `ACC-A1` (`100000011234`): `50,000,000.00`<br/>• `ACC-A2` (`100000015678`): `10,000,000.00` | Chuyển tiền cùng chủ (UC-005), chuyển khác chủ (UC-006), test tranh chấp số dư (D1) |
| `cust_tran_b` | `CUSTOMER` | `CUSTOMER` | • `ACC-B1` (`100000029999`): `5,000,000.00`<br/>• `ACC-B2` (`100000020000`): `0.00` (`LOCKED`) | Tài khoản nhận tiền, test chuyển vào tài khoản bị khóa (`ACCOUNT_INACTIVE`) |
| `ops_maker` | `STAFF` | `OPERATIONS` | *(Không có)* | Tra soát giao dịch, review `fraud_flag`, tạo yêu cầu đổi hạn mức (Maker) |
| `ops_checker` | `STAFF` | `OPERATIONS` + `CHECKER` | *(Không có)* | Duyệt yêu cầu đổi hạn mức (Checker ≠ Maker) |
| `admin_maker` | `STAFF` | `ADMIN` | *(Không có)* | Tạo yêu cầu cấp/thu hồi Role/Privilege |
| `admin_checker` | `STAFF` | `ADMIN` + `CHECKER` | *(Không có)* | Duyệt yêu cầu thay đổi Role/Privilege, Retention/Legal Hold |
| `auditor_ro` | `STAFF` | `AUDITOR` | *(Không có)* | Tra cứu và export `audit_log` (Read-only, D4) |

**Hạn mức khởi tạo mặc định (`limit_config` ACTIVE cho mỗi Customer demo):**
- `per_transaction_limit`: `20,000,000.00 VND`
- `daily_limit`: `100,000,000.00 VND`
