# BE-AUD-01 — Đặc tả Audit Writer và Internal Port

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-AUDIT-01` (`Task: BE-AUD-01`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai component (90 tests Audit + 44 tests Common JCS + ArchUnit PASS — chờ ghép nối caller liên module)
> **Owner:** Thịnh · **Reviewer:** Phương (`common`, `ledger`), Đạt (`identity`, `admin`), Vũ (`transfer`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.6), [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]], [[digitalbanking-docs/docs-common/09. Test plan.md]], [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]], [[DECISIONS.md]] (`DEC-COM-001/002`, `DEC-TRF-007`)

---

## 1. Mục tiêu và Tái sử dụng Nền tảng `common`

`BE-AUD-01` cung cấp thành phần ghi nhật ký kiểm toán (`C1`/`C2`/`C3`/`C4`) cho các module nghiệp vụ, đảm bảo bảng `banking.audit_log` bất biến (`append-only`), nối chuỗi băm mật mã liên tiếp (`SHA-256` hash chain) và bảo toàn tính nguyên tử của giao dịch (`AC-034`).

Module `audit` tái sử dụng trực tiếp các thành phần của `common`:

| Năng lực | Thành phần `common` | Cách `audit` sử dụng |
|---|---|---|
| Chuẩn hóa JSON (RFC 8785) | `common.crypto.JcsCanonicalizer` | Chuẩn hóa `canonical_payload` sang mảng byte UTF-8 tất định (từ chối khóa trùng lặp). |
| Băm mật mã (`SHA-256`) | `common.crypto.HmacService.sha256Hex` | Tính mã băm `record_hash` 64 ký tự hex chữ thường. |
| Ngữ cảnh truy vết | `common.context.RequestContextHolder` | Lấy `correlationId` và `requestId` tại ranh giới tích hợp. |
| Che dấu PII | `common.crypto.PiiMaskingUtils` | Caller chuẩn bị các trường đã che dấu; `AuditEventValidator` cưỡng chế allowlist. |
| Xử lý lỗi & Fallback | `common.error.ErrorCode.AUDIT_WRITE_FAILED`, `SecurityAuditFallbackLog` | Ánh xạ lỗi ghi kiểm toán tại biên API và ghi log dự phòng cho sự kiện `C3`. |

`AuditCanonicalJson` đóng vai trò lớp chuyển đổi dữ liệu Java và kiểm tra giới hạn số nguyên an toàn / độ sâu cấu trúc riêng của `audit`, sau đó ủy quyền hoàn toàn thuật toán RFC 8785 cho `common.crypto.JcsCanonicalizer`.

---

## 2. Phạm vi Triển khai (`Scope`)

### 2.1. Các thành phần đã triển khai độc lập
- Danh mục hành động kiểm toán chuẩn (`AuditActionCatalog`) kèm siêu dữ liệu loại tác nhân (`actor_type`) và loại thực thể (`entity_type`) hợp lệ theo `design/01` §5.3.
- Bản ghi sự kiện bất biến `AuditEvent` thực hiện sao chép sâu (`deep copy`) và chuẩn hóa dữ liệu (`AuditSnapshotValues`) ngay khi khởi tạo.
- Bộ kiểm tra hợp lệ `AuditEventValidator`: kiểm tra allowlist từng loại thực thể (`design/01` §5.6), giới hạn kích thước payload (`<= 32 KiB`, độ sâu `<= 6`, tối đa `4096` nút) và kiểm tra thông tin client đã che dấu (`client_info_masked`).
- Giao thức tính chuỗi băm `v1` (`AuditHashing`).
- Dịch vụ điều phối `AuditRecorder` (`Propagation.MANDATORY` cho `C1`/`C2`), cổng outbound `AuditRecordWriterPort` (`application/port`) và adapter `AuditJdbcWriter` (`adapter/out`).
- Migration tạo bảng `banking.audit_log`, trigger chặn `UPDATE/DELETE/TRUNCATE`, phân quyền `banking_app` và bộ kiểm thử tích hợp trên PostgreSQL 16 Testcontainers.

### 2.2. Hạng mục ghép nối liên module tiếp theo
- Mở cổng công khai `AuditModuleApi` (`recordInTransaction` cho `C1`/`C2` và `recordStandalone` cho `C3`) và kết nối trực tiếp với `identity`, `transfer`, `fraud`, `admin`.
- Đánh số lại migration từ `V2__create_audit_log.sql` thành `V5__create_audit_log.sql` theo chuỗi chuẩn `DEC-COM-001`.

---

## 3. Kiến trúc Phân lớp & Hợp đồng Giao tiếp (`BE-ARCH-00` §4.2)

Tuân thủ kiến trúc Hexagonal (`adapter/in -> application -> domain` và `application -> application/port <- adapter/out`):
- **`AuditRecordRef`** (`com.simulatedbank.banking.audit.application.port.AuditRecordRef`): Record bất biến `(long auditId, String recordHash, short hashVersion)`.
- **`AuditRecordWriterPort`** (`com.simulatedbank.banking.audit.application.port.AuditRecordWriterPort`): Outbound port định nghĩa thao tác ghi bản ghi kiểm toán.
- **`AuditRecorder`** (`com.simulatedbank.banking.audit.application.AuditRecorder`): Quản lý ranh giới giao dịch `@Transactional(propagation = Propagation.MANDATORY)`, kiểm tra phân loại `C1`/`C2`, thực thi `AuditEventValidator.validate`, đánh dấu `rollback-only` nếu phát sinh lỗi và ủy quyền ghi DB cho `AuditRecordWriterPort`.
- **`AuditJdbcWriter`** (`com.simulatedbank.banking.audit.adapter.out.AuditJdbcWriter`): Hiện thực `AuditRecordWriterPort`, kiểm tra mức cô lập `SHOW transaction_isolation == 'read committed'`, lấy khóa tuần tự `SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, đọc `prev_hash`, tính `record_hash` và thực hiện `INSERT ... RETURNING audit_id`.

Hợp đồng cổng nội bộ cung cấp cho các module khác (`design/05` §11.1):

```java
public interface AuditModuleApi {
    AuditRecordRef recordInTransaction(AuditEvent event);
    Optional<AuditRecordRef> recordStandalone(AuditEvent event);
}
```

---

## 4. Quy tắc Kiểm tra Sự kiện & Chuẩn hóa Snapshot (`AuditEventValidator`)

Bộ kiểm tra cưỡng chế các quy tắc:
- `actor_type` phải thuộc tập cho phép của `action`: `USER` yêu cầu `actor_user_id > 0`; `SYSTEM` có `actor_user_id = null`.
- `actor_role` không được rỗng; `entity_type` khớp với `action`; `entity_id` đúng định dạng an toàn.
- `outcome` thuộc `SUCCESS`, `FAILURE`, `REJECTED`.
- `correlation_id` và `request_id` đúng mẫu ký tự an toàn và độ dài giới hạn (`<= 64`).
- `amount` / `currency`: tiền tệ `VND`, không âm, tối đa 2 chữ số thập phân; nếu `amount == null` thì `currency` phải `null`.
- `before_state` và `after_state` tuân thủ nghiêm ngặt allowlist theo từng `entity_type` (`design/01` §5.6): chuẩn hóa ID thành chuỗi số, tiền tệ thành chuỗi thập phân cố định 2 chữ số lẻ (`"150000.00"`), bộ đếm thành số nguyên an toàn; cấm tuyệt đối số tài khoản đầy đủ, CCCD, mật khẩu, PIN, OTP, token, SĐT/email hoặc văn bản `description` / `notes` tự do.

---

## 5. Giao thức Chuỗi băm (`Hash Chain Protocol v1` — `design/01` §7.2–7.3)

- Payload chuẩn hóa JCS gồm 16 trường cố định của phiên bản `hash_version = 1`: `occurred_at` (UTC 6 chữ số thập phân microsecond), `actor_type`, `actor_user_id`, `actor_role`, `action`, `entity_type`, `entity_id`, `before_state`, `after_state`, `amount`, `currency`, `correlation_id`, `request_id`, `outcome`, `client_info_masked`, `hash_version`.
- Công thức tính mã băm:
  $$\text{record\_hash} = \text{lowercase\_hex}\!\left(\text{SHA-256}\!\left(\text{UTF-8}(\text{JCS}(\text{canonical\_payload})) \;\|\; \text{"|"} \;\|\; \text{prev\_hash}\right)\right)$$
- Bản ghi đầu tiên trong chuỗi (`genesis`) dùng `prev_hash` gồm 64 ký tự `'0'`.
- Bắt buộc chạy ở mức cô lập **`READ COMMITTED`** trước khi lấy `pg_advisory_xact_lock` để luôn nhìn thấy bản ghi vừa commit của giao dịch liền trước.

---

## 6. Thứ tự Thực thi trong Giao dịch Nghiệp vụ (`DEC-TRF-007`)

1. Kiểm tra tính hợp lệ của `AuditEvent` trước khi lấy khóa DB.
2. Trong giao dịch nghiệp vụ của caller (`T1`, `T2` hoặc giao dịch phê duyệt `admin`), caller hoàn tất và `flush` toàn bộ các câu lệnh ghi nghiệp vụ trước.
3. Ở pha cuối cùng trước khi commit, caller gọi `recordInTransaction` (trong `T2`, ghi tuần tự từng sự kiện `FRAUD_FLAG_CREATED` `C2` theo `flag_id ASC`, sau đó ghi sự kiện `TRANSFER_COMPLETED`/`FAILED` `C1`).
4. `AuditJdbcWriter` lấy khóa `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, đọc `prev_hash`, chèn bản ghi vào `banking.audit_log` và trả về `AuditRecordRef`.
5. Nếu xảy ra bất kỳ lỗi nào khi ghi audit `C1`/`C2`, giao dịch của caller bị đánh dấu `rollback-only` và rollback toàn bộ (`fail-closed`).

---

## 7. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.4` | 11–12/11/2026 | Khởi tạo đặc tả writer, giao thức chuỗi băm và đồng bộ với module `common`. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt, cập nhật kiến trúc Hexagonal (`AuditRecordWriterPort` / `AuditJdbcWriter`) và đồng bộ `DEC-COM-001`, `DEC-TRF-007`. |
