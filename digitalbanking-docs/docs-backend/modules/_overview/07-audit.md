# Module 07: `audit` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-07`
> **Module:** `com.simulatedbank.banking.audit`
> **Owner chính:** **Thịnh** (Phối hợp: Đạt — `identity` & `admin`, Vũ & Phương — `transfer` & `ledger`, Thắng — FE)
> **Mốc tập trung:** **Tuần 3 – Tuần 5 (W3–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-034..038, FR-053), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.6), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.7), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (Toàn bộ §2–§13)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `audit` cung cấp hệ thống bằng chứng kiểm toán nghiệp vụ bất biến (**Append-only, Tamper-Evident Audit Trail**) cho toàn bộ các giao dịch tiền tệ và hành động nhạy cảm. Module quản lý việc nối chuỗi băm mật mã liên tiếp (`SHA-256` hash chain), đảm bảo truy vết đầu-cuối qua `correlation_id`, cung cấp API tra cứu/xuất bằng chứng kiểm toán cho Ops/Auditor, và thực thi chính sách lưu trữ (`Retention Policy`) cùng giữ pháp lý (`Legal Hold`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `audit` sở hữu độc quyền 3 bảng chính (và 1 bảng checkpoint đề xuất trong `design/01` §6.5.2):

| Bảng | Mục đích & Ràng buộc Trọng tâm | Quy tắc Bảo toàn Bằng chứng |
|---|---|---|
| **`banking.audit_log`** | Lưu nhật ký kiểm toán với `audit_id` (`IDENTITY PK`), `occurred_at`, `actor_type`, `actor_user_id`, `actor_role`, `action`, `entity_type`, `entity_id`, `before_state`, `after_state`, `amount`, `currency`, `correlation_id`, `request_id`, `outcome`, `prev_hash`, `record_hash`, `hash_version` | **Append-only tuyệt đối (`I-AUD-02`):** Tài khoản DB `banking_app` chỉ có quyền `SELECT, INSERT` (không có quyền `UPDATE, DELETE`), kết hợp trigger chặn sửa xóa. |
| **`banking.legal_hold`** | Quản lý lệnh giữ pháp lý (`hold_id`, `entity_type`, `entity_id`, `status = ACTIVE / RELEASED`, `placed_by`, `released_by`, `approval_id`) | **Ưu tiên cao hơn Retention (`I-AUD-06`):** Chặn mọi lệnh xóa/purge đối với dữ liệu đang nằm trong phạm vi giữ pháp lý `ACTIVE`. |
| **`banking.retention_policy`** | Chính sách thời hạn lưu trữ theo `data_class` (`AUDIT_LOG`, `TRANSFER`, `LEDGER_ENTRY`, ...) | Mặc định lưu trữ tối thiểu `5 năm` (`1,825 ngày` đề xuất) cho dữ liệu tài chính và kiểm toán. |
| **`banking.audit_chain_checkpoint`** *(Đề xuất `design/01`)* | Lưu các mốc kiểm định chuỗi băm (`VERIFIED`, `PURGE`, `ANCHOR`) với `through_audit_id` và `through_record_hash` | Giúp kiểm định chuỗi băm tăng dần (incremental verification) và giữ gốc kiểm định sau khi purge dữ liệu cũ. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.7)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-AUD-01` | `GET /api/v1/audit` | `STAFF` (`AUDIT_READ`) | Tra cứu nhật ký kiểm toán đa tiêu chí (`correlationId`, `actorUserId`, `action`, `entityType`, `entityId`, `outcome`, `from`–`to`). Thao tác đọc không sinh thêm bản ghi audit mới (`DD-AUD-11`). |
| `API-AUD-02` | `GET /api/v1/audit/{auditId}` | `STAFF` (`AUDIT_READ`) | Xem chi tiết một bản ghi kiểm toán kèm `before_state`, `after_state`, `prev_hash`, `record_hash`. |
| `API-AUD-03` | `GET /api/v1/audit/export` | `STAFF` (`AUDIT_EXPORT`) | Xuất bằng chứng kiểm toán ra `CSV` hoặc `JSONL` kèm manifest hash. **Bắt buộc ghi thành công sự kiện `AUDIT_EXPORTED` (`C2`) trước khi trả file.** |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/01` §4.3)

Module `audit` cung cấp interface **`AuditModuleApi`** cho tất cả các module khác:

1. **`recordInTransaction(AuditEvent event)` (`Propagation.MANDATORY`):**
   - Dùng cho nhóm **`C1` (Financial Atomic: `TRANSFER_CREATED`, `TRANSFER_COMPLETED`, `TRANSFER_FAILED`, `TRANSFER_RECONCILED`)** và nhóm **`C2` (Sensitive State Atomic: `AUTH_LOGIN_SUCCESS`, `AUTH_STEP_UP_VERIFIED`, `LIMIT_CHANGE_*`, `ROLE_CHANGE_*`, `FRAUD_FLAG_CREATED`, `FRAUD_FLAG_REVIEWED`, `LEGAL_HOLD_*`)**.
   - **Nguyên tắc Fail-closed (`AC-034`):** Nếu ghi audit thất bại, ném ngoại lệ để rollback toàn bộ giao dịch nghiệp vụ hiện tại.
   - **Nguyên tắc giữ khóa ngắn nhất:** Luôn được gọi ở thao tác DB cuối cùng ngay trước khi transaction commit.
2. **`recordStandalone(AuditEvent event)` (`Propagation.REQUIRES_NEW`):**
   - Dùng cho nhóm **`C3` (Security Best-effort: `AUTH_LOGIN_FAILED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_SESSION_LOGOUT`, `AUTH_STEP_UP_FAILED`, `SECURITY_ACCESS_DENIED`, `SECURITY_IDEMPOTENCY_CONFLICT`)**.
   - Chạy trong transaction riêng để lưu lại bằng chứng kể cả khi request nghiệp vụ bị từ chối (`401`/`403`/`409`/`423`).

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `AuditRecorder` kiểm tra **Audit Action Catalog** (30+ actions chuẩn tại `design/01` §5.3) và **PII Allowlist** (`design/01` §5.6 — cấm tuyệt đối full account number, mật khẩu, OTP, token, tên/email/SĐT, và nội dung `description` tự do).
2. [ ] Hiện thực `AuditWriter` lấy khóa tuần tự `SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, đọc `record_hash` cuối cùng (genesis dùng `64` ký tự `'0'`), chuẩn hóa JSON theo **RFC 8785 JCS**, và tính `record_hash = SHA-256(JCS + "|" + prev_hash)`.
3. [ ] Hiện thực `AuditQueryService` và `AuditExportService` (stream CSV/JSONL kèm header manifest hash).
4. [ ] Hiện thực `AuditVerificationJob` (kiểm định tính liên tục của chuỗi băm) và viết bộ test kiểm chứng `T-AUD-01..23` (đặc biệt là `AC-034`: lỗi ghi audit làm rollback giao dịch chuyển tiền).
