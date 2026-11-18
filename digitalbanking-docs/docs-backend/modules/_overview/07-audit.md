# Module 07: `audit` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-07`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.audit`
> **Owner chính:** **Thịnh** (Phối hợp: Đạt — `identity` & `admin`, Vũ & Phương — `transfer` & `ledger`, Thắng — FE)
> **Mốc tập trung:** **Tuần 3 – Tuần 5 (W3–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-034..038, FR-053), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.6), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §8.5), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.6), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (Toàn bộ §2–§13), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.1, §13.4), [[DECISIONS.md]] (`DEC-COM-001/002`, `DEC-TRF-007`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `audit` cung cấp hệ thống bằng chứng kiểm toán nghiệp vụ bất biến (**Append-only, Tamper-Evident Audit Trail**) cho toàn bộ các giao dịch tiền tệ và hành động nhạy cảm. Module quản lý việc nối chuỗi băm mật mã liên tiếp (`SHA-256` hash chain trên dữ liệu chuẩn hóa RFC 8785 JCS), đảm bảo truy vết đầu-cuối qua `correlation_id`, cung cấp API tra cứu/xuất bằng chứng kiểm toán cho nhân viên vận hành và kiểm toán viên (`OPERATIONS`, `ADMIN`, `AUDITOR`), và thực thi chính sách lưu trữ (`Retention Policy`) cùng giữ pháp lý (`Legal Hold`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `audit` sở hữu độc quyền 4 bảng (`04. Database design & analysis.md` §4.6, `design/01` §6.5, migration `V5` theo `DEC-COM-001`):

| Bảng | Mục đích & Ràng buộc Trọng tâm | Quy tắc Bảo toàn Bằng chứng |
|---|---|---|
| **`banking.audit_log`** | Lưu nhật ký kiểm toán với `audit_id BIGINT IDENTITY PK`, `occurred_at`, `actor_type`, `actor_user_id`, `actor_role`, `action`, `entity_type`, `entity_id`, `before_state JSONB`, `after_state JSONB`, `amount`, `currency`, `correlation_id`, `request_id`, `outcome`, **`client_info_masked JSONB`**, **`canonical_payload JSONB NOT NULL`**, `prev_hash CHAR(64) NOT NULL`, `record_hash CHAR(64) NOT NULL`, `hash_version SMALLINT NOT NULL` | **Append-only tuyệt đối (`I-AUD-02`, `DEC-COM-002`):** Tài khoản DB `banking_app` chỉ có quyền `SELECT, INSERT` (thu hồi `UPDATE, DELETE, TRUNCATE`), kết hợp trigger mức câu lệnh chặn mọi thao tác sửa/xóa. Cột `canonical_payload` lưu biểu diễn chuẩn phục vụ `AuditChainVerifier` tái chuẩn hóa qua `JcsCanonicalizer` và đối chiếu khớp với từng cột hiển thị. |
| **`banking.legal_hold`** | Quản lý lệnh giữ pháp lý (`hold_id`, `scope_type`, `scope_id`, `status IN ('ACTIVE','RELEASED')`, `placed_by`, `released_by`, `approval_request_id`) | **Ưu tiên cao hơn Retention (`I-AUD-06`):** Chặn mọi lệnh xóa/purge đối với dữ liệu đang nằm trong phạm vi giữ pháp lý `ACTIVE`. |
| **`banking.retention_policy`** | Chính sách thời hạn lưu trữ theo `data_class` (`AUDIT_LOG`, `TRANSFER`, `LEDGER_ENTRY`, ...) | Mặc định lưu trữ tối thiểu `5 năm` (`1.825 ngày`) cho dữ liệu tài chính và kiểm toán. |
| **`banking.audit_chain_checkpoint`** | Lưu các mốc kiểm định chuỗi băm (`VERIFIED`, `PURGE`, `ANCHOR`) với `through_audit_id` và `through_record_hash` | Hỗ trợ kiểm định chuỗi băm tăng dần (incremental verification) và giữ gốc kiểm định sau khi lưu trữ/purge dữ liệu cũ. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.6)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-AUD-01` | `GET /api/v1/audit` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`AUDIT_READ`) | Tra cứu nhật ký kiểm toán đa tiêu chí (`correlationId`, `reference`, `actorUserId`, `actorType`, `action`, `entityType`, `entityId`, `outcome`, `from`–`to`, `sort=occurredAt,desc\|asc`). Thao tác đọc không sinh thêm bản ghi audit mới (`DD-AUD-11`). |
| `API-AUD-02` | `GET /api/v1/audit/{auditId}` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`AUDIT_READ`) | Xem chi tiết một bản ghi kiểm toán kèm `beforeState`, `afterState`, `clientInfo`, `prevHash`, `recordHash` (qua bộ lọc an toàn `AuditDetailProjection`). |
| `API-AUD-03` | `GET /api/v1/audit/export` | Chỉ **`AUDITOR`** (`AUDIT_EXPORT`) | Xuất bằng chứng kiểm toán ra `CSV` hoặc `JSONL` (tối đa `10.000` dòng; vượt ngưỡng trả `413 EXPORT_TOO_LARGE`). **Bắt buộc ghi và commit thành công sự kiện `AUDIT_EXPORTED` (`C2`) trước khi truyền dữ liệu file về client.** |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/01` §4.3, `design/05` §11.1)

Module `audit` cung cấp các giao diện ghi và truy vấn bằng chứng kiểm toán:

1. **`recordInTransaction(AuditEvent event)` (`Propagation.MANDATORY`):**
   - Dùng cho nhóm **`C1` (Financial Atomic: `TRANSFER_CREATED`, `TRANSFER_COMPLETED`, `TRANSFER_FAILED`, `TRANSFER_RECONCILED`)** và nhóm **`C2` (Sensitive State Atomic: `AUTH_LOGIN_SUCCESS`, `AUTH_CHALLENGE_CREATED`, `AUTH_STEP_UP_VERIFIED`, `LIMIT_CHANGE_*`, `ROLE_CHANGE_*`, `FRAUD_FLAG_CREATED`, `FRAUD_FLAG_REVIEWED`, `LEGAL_HOLD_*`, `AUDIT_EXPORTED`)**.
   - **Nguyên tắc Fail-closed (`AC-034`, `DEC-TRF-007`):** Nếu ghi audit thất bại, đánh dấu rollback và ném ngoại lệ để rollback toàn bộ giao dịch nghiệp vụ hiện tại.
   - **Nguyên tắc giữ khóa ngắn nhất:** Luôn được gọi ở thao tác DB cuối cùng ngay trước khi transaction commit (`SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`).
2. **`recordStandalone(AuditEvent event)` (`Propagation.REQUIRES_NEW`):**
   - Dùng cho nhóm **`C3` (Security Best-effort: `AUTH_LOGIN_FAILED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_SESSION_LOGOUT`, `AUTH_CHALLENGE_LOCKED`, `AUTH_STEP_UP_FAILED`, `SECURITY_ACCESS_DENIED`, `SECURITY_IDEMPOTENCY_CONFLICT`)**.
   - Chạy trong transaction riêng để lưu lại bằng chứng kể cả khi request nghiệp vụ bị từ chối (`401`/`403`/`409`/`423`); nếu lỗi ghi DB thì kích hoạt `SecurityAuditFallbackLog` + metric cảnh báo mà không làm đổi phản hồi từ chối.

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Hiện thực domain & validator kiểm toán (`AuditActionCatalog`, `AuditEvent`, `AuditEventValidator`, `AuditSnapshotValues`, `AuditCanonicalJson` ủy quyền RFC 8785 cho `common.crypto.JcsCanonicalizer`, `AuditHashing` dùng `HmacService.sha256Hex`) (`BE-AUD-01`).
2. [x] Hiện thực `AuditRecorder` (`MANDATORY`), outbound port `AuditRecordWriterPort`, `AuditJdbcWriter` (`READ COMMITTED` guard + `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`) và migration schema/triggers/permissions cùng 14 PostgreSQL concurrency/rollback integration tests (`BE-AUD-01`).
3. [x] Hiện thực `AuditSearchQuery`, `AuditQueryPort`, `AuditQueryJdbcStore`, `AuditQuerySql`, `AuditDetailProjection` (bảo vệ read-time allowlist 14 entity types) và `AuditQueryService` (`BE-AUD-02`).
4. [x] Hiện thực `AuditChainVerifier` và `AuditChainJdbcVerifier` (quét theo lô keyset `1.000` bản ghi, tái chuẩn hóa JCS và đối chiếu khớp toàn bộ cột DB với `canonical_payload`) — tổng cộng **171 tests PASS** trên PostgreSQL 16 Testcontainers (`BE-AUD-01/02`).
5. [ ] Đánh số migration sang `V5__create_audit_log.sql` theo chuỗi `DEC-COM-001`, mở public port `AuditModuleApi` (gồm cả `C3` standalone), hoàn thiện `AuditExportService` / `AuditController` (`BE-AUD-02`) và cơ chế `Retention` / `Legal Hold` (`BE-AUD-03`).

**Tài liệu chi tiết module `audit`:**
- [Chỉ mục Module Audit](../audit/README.md)
- [BE-AUD-01 — Audit Writer](../audit/BE-AUD-01-audit-writer.md) ([Báo cáo kiểm chứng](../audit/BE-AUD-01-verification-report.md))
- [BE-AUD-02 — Query, Export & Verifier](../audit/BE-AUD-02-query-export-verification.md) ([Báo cáo kiểm chứng](../audit/BE-AUD-02-verification-report.md))
- [BE-AUD-03 — Retention & Legal Hold](../audit/BE-AUD-03-retention-legal-hold.md) ([Báo cáo kiểm chứng](../audit/BE-AUD-03-verification-report.md))
