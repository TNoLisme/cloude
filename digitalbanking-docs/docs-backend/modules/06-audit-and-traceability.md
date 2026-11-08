# Module 06: Audit & Traceability (`audit`)

| Trường | Giá trị |
|---|---|
| **Dự án** | Digital Banking Platform Simulator (Single-Bank, Core Simulated) |
| **Mã tài liệu** | `BE-MOD-06` |
| **Phiên bản** | v1.0 |
| **Ngày cập nhật** | 2026-11-07 |
| **Trạng thái** | Hoàn thành — Tài liệu tham chiếu chuẩn cho Backend Developer & Coding Agent |
| **Nguồn tham chiếu** | `[[digitalbanking-docs/docs-common/03. SRS]]` (§3.7), `[[digitalbanking-docs/docs-common/04. Database design & analysis]]` (§5.6), `[[digitalbanking-docs/docs-common/06. System architecture]]` (§4.3, §5.4), `[[digitalbanking-docs/docs-common/07. API contracts]]` (§11.7), `[[digitalbanking-docs/docs-common/design/01. audit-traceability]]` (Toàn bộ §2–§12) |

---

## 1. Phạm vi & Trách nhiệm Module

Module `audit` (`com.simulatedbank.banking.audit`) là nền tảng bằng chứng nghiệp vụ bất biến (append-only, tamper-evident) của toàn hệ thống:

1. **Write-owner độc quyền** của 3 bảng:
   - `banking.audit_log`: Sổ nhật ký kiểm toán nối chuỗi băm mật mã (`SHA-256` hash chain).
   - `banking.legal_hold`: Quản lý lệnh giữ dữ liệu pháp lý chống xóa/lưu trữ.
   - `banking.retention_policy`: Quản lý chính sách thời hạn lưu trữ theo lớp dữ liệu.
2. **Cung cấp Internal Port `AuditInternalPort` (`AuditModuleApi`)** cho tất cả các module khác ghi nhận sự kiện kiểm toán theo đúng phân loại giao dịch (`C1`/`C2` cùng transaction Fail-closed; `C3` trong transaction độc lập `REQUIRES_NEW`).
3. **Cung cấp 3 REST API** dành cho `OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`:
   - `GET /api/v1/audit` (`API-AUD-01`): Tra cứu nhật ký kiểm toán đa tiêu chí.
   - `GET /api/v1/audit/{auditId}` (`API-AUD-02`): Xem chi tiết bản ghi kiểm toán và thông tin chuỗi băm.
   - `GET /api/v1/audit/export` (`API-AUD-03`): Xuất bằng chứng kiểm toán ra `CSV` hoặc `JSONL` kèm chữ ký băm (`X-Export-SHA256`).
4. **Vận hành `AuditChainVerificationScheduler`**: Tiến trình kiểm định tính toàn vẹn của chuỗi băm từ Genesis (`000...000`).

---

## 2. Cấu trúc Package chuẩn

```text
com.simulatedbank.banking.audit
├── api
│   ├── AuditController.java                    // GET /api/v1/audit, GET /api/v1/audit/{auditId}, GET /api/v1/audit/export
│   └── dto
│       ├── AuditSearchFilter.java
│       ├── AuditSummaryResponse.java
│       └── AuditDetailResponse.java
├── application
│   ├── AuditInternalPort.java                  // Public SPI cho các module khác gọi ghi audit
│   ├── AuditRecorderValidator.java             // Kiểm tra Action Catalog, Required fields & PII Allowlist
│   ├── AuditHashChainWriter.java               // Lấy pg_advisory_xact_lock, chuẩn hóa JCS, tính SHA-256, INSERT
│   ├── AuditQueryAndExportService.java         // Truy vấn read-only & stream CSV/JSONL export
│   └── AuditChainVerificationScheduler.java    // Job kiểm tra tính liên tục prev_hash -> record_hash
└── infrastructure
    ├── AuditLogEntity.java                     // Bảng banking.audit_log (Immutable)
    ├── LegalHoldEntity.java                    // Bảng banking.legal_hold
    ├── RetentionPolicyEntity.java              // Bảng banking.retention_policy
    └── AuditJdbcRepository.java                // Native SQL advisory lock & batch verification query
```

---

## 3. Phân loại Sự kiện & Ngữ nghĩa Giao dịch (`C1` – `C4`)

| Phân loại | Tên gọi | Propagation | Hành vi khi Ghi Audit Thất bại | Ví dụ Điển hình |
|---|---|---|---|---|
| **`C1`** | **Financial Atomic** | `Propagation.MANDATORY` (Cùng TX `T1`/`T2`) | **Fail-closed:** Ném `AuditWriteFailedException` -> Rollback toàn bộ giao dịch tiền tệ (`T1` hoặc `T2`), trả về `500 AUDIT_WRITE_FAILED`. | `TRANSFER_CREATED`, `TRANSFER_COMPLETED`, `TRANSFER_FAILED`, `TRANSFER_RECONCILED` |
| **`C2`** | **Sensitive State Atomic** | `Propagation.MANDATORY` (Cùng TX đổi trạng thái) | **Fail-closed:** Rollback thay đổi cấu hình/quyền/trạng thái; với `AUDIT_EXPORTED` thì không trả file xuất nếu ghi audit lỗi. | `AUTH_LOGIN_SUCCESS`, `AUTH_STEP_UP_VERIFIED`, `LIMIT_CHANGE_REQUESTED`, `LIMIT_CHANGE_APPROVED`, `ROLE_CHANGE_APPROVED`, `FRAUD_FLAG_CREATED`, `FRAUD_FLAG_REVIEWED`, `AUDIT_EXPORTED` |
| **`C3`** | **Security Rejection / Control** | `Propagation.REQUIRES_NEW` (TX độc lập) | **Best-effort có kiểm soát:** Giữ nguyên kết quả từ chối bảo mật hoặc khóa tài khoản đã commit; nếu DB lỗi thì ghi fallback vào JSON Security Log kèm cảnh báo `CRITICAL`. | `AUTH_LOGIN_FAILED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_SESSION_LOGOUT`, `AUTH_STEP_UP_FAILED`, `SECURITY_ACCESS_DENIED`, `SECURITY_IDEMPOTENCY_CONFLICT` |
| **`C4`** | **Operational / System Job** | Transaction riêng của Background Job | Ghi log lỗi kỹ thuật, tăng metric và thử lại ở chu kỳ sau. | `FRAUD_ENGINE_ERROR`, `RETENTION_ARCHIVED` |

> [!IMPORTANT]
> **Quy tắc vị trí ghi Audit trong `T1` và `T2` (`DD-AUD-03`):**
> Vì `AuditHashChainWriter` phải lấy khóa tuần tự toàn cục `pg_advisory_xact_lock`, lệnh gọi `auditInternalPort.recordInTransaction(...)` **bắt buộc là câu lệnh SQL cuối cùng ngay trước khi transaction commit**. Tuyệt đối không thực hiện bất kỳ lời gọi mạng hay xử lý nặng nào sau khi đã lấy advisory lock của chuỗi audit.

---

## 4. Danh mục Chuẩn hóa `AuditAction` & `PII Allowlist`

### 4.1. Danh mục Hành động Kiểm toán (`AuditAction` Enum)

| Nhóm | Mã `action` | Class | `target_type` (`entity_type`) | `target_id` (`entity_id`) | `result` (`outcome`) |
|---|---|---|---|---|---|
| **Auth & Session** | `AUTH_LOGIN_SUCCESS` | `C2` | `USER` | `user_id` | `SUCCESS` |
| | `AUTH_LOGIN_FAILED` | `C3` | `USER` | `user_id` hoặc `anon:<sha256_16>` | `FAILURE` |
| | `AUTH_ACCOUNT_LOCKED` | `C3` | `USER` | `user_id` | `SUCCESS` |
| | `AUTH_SESSION_LOGOUT` | `C3` | `SESSION` | `session_id` | `SUCCESS` |
| | `AUTH_CHALLENGE_CREATED` | `C2` | `AUTH_CHALLENGE` | `challenge_id` | `SUCCESS` |
| | `AUTH_CHALLENGE_RESENT` | `C2` | `AUTH_CHALLENGE` | `challenge_id` | `SUCCESS` |
| | `AUTH_CHALLENGE_LOCKED` | `C3` | `AUTH_CHALLENGE` | `challenge_id` | `SUCCESS` |
| | `AUTH_STEP_UP_VERIFIED` | `C2` | `AUTH_CHALLENGE` | `challenge_id` | `SUCCESS` |
| | `AUTH_STEP_UP_FAILED` | `C3` | `AUTH_CHALLENGE` | `challenge_id` | `FAILURE` |
| **Transfer** | `TRANSFER_CREATED` | `C1` | `TRANSFER` | `transfer.reference` | `SUCCESS` |
| | `TRANSFER_COMPLETED` | `C1` | `TRANSFER` | `transfer.reference` | `SUCCESS` |
| | `TRANSFER_FAILED` | `C1` | `TRANSFER` | `transfer.reference` | `FAILURE` |
| | `TRANSFER_RECONCILED` | `C1` | `TRANSFER` | `transfer.reference` | `SUCCESS` / `FAILURE` |
| **Limit & Admin** | `LIMIT_CHANGE_REQUESTED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `SUCCESS` |
| | `LIMIT_CHANGE_APPROVED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `SUCCESS` |
| | `LIMIT_CHANGE_REJECTED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `REJECTED` |
| | `ROLE_CHANGE_REQUESTED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `SUCCESS` |
| | `ROLE_CHANGE_APPROVED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `SUCCESS` |
| | `ROLE_CHANGE_REJECTED` | `C2` | `APPROVAL_REQUEST` | `approval_id` | `REJECTED` |
| **Account** | `ACCOUNT_STATUS_CHANGED` | `C2` | `ACCOUNT` | `account_id` | `SUCCESS` |
| **Fraud** | `FRAUD_FLAG_CREATED` | `C2` | `FRAUD_FLAG` | `fraud_flag_id` | `SUCCESS` |
| | `FRAUD_FLAG_REVIEWED` | `C2` | `FRAUD_FLAG` | `fraud_flag_id` | `SUCCESS` |
| | `FRAUD_ENGINE_ERROR` | `C4` | `TRANSFER` | `transfer.reference` | `FAILURE` |
| **Compliance & Security** | `AUDIT_EXPORTED` | `C2` | `AUDIT_LOG` | `exportId` | `SUCCESS` |
| | `LEGAL_HOLD_PLACED` | `C2` | `LEGAL_HOLD` | `hold_id` | `SUCCESS` |
| | `LEGAL_HOLD_RELEASED` | `C2` | `LEGAL_HOLD` | `hold_id` | `SUCCESS` |
| | `RETENTION_POLICY_CHANGED` | `C2` | `RETENTION_POLICY` | `policy_id` | `SUCCESS` |
| | `RETENTION_PURGED` | `C2` | `RETENTION_POLICY` | `policy_id` | `SUCCESS` |
| | `SECURITY_ACCESS_DENIED` | `C3` | `<TARGET_ENTITY>` | `<target_id>` | `REJECTED` |
| | `SECURITY_IDEMPOTENCY_CONFLICT` | `C3` | `TRANSFER` | `keyHash` | `REJECTED` |

### 4.2. Quy tắc Kiểm duyệt `metadata` / `before_state` / `after_state` (`PII Allowlist`)

`AuditRecorderValidator` kiểm tra mọi JSON payload trước khi ghi vào `banking.audit_log`:
- **Cấm tuyệt đối** các trường nhạy cảm: `password`, `passwordHash`, `otpCode`, `otpHash`, `sessionToken`, `csrfToken`, `accountNumber` (số đầy đủ), `nationalId` (số đầy đủ), `fullName`, `email`, `phone`, và trường văn bản tự do `description` của lệnh chuyển tiền.
- Chỉ cho phép lưu các trường định danh kỹ thuật và trạng thái: `reference`, `status`, `failureCode`, `sourceAccountId`, `destAccountId`, `amount`, `currency`, `sourceBalanceAfter`, `destBalanceAfter`, `ruleCode`, `severity`, `decision`, `perTxLimit`, `dailyLimit`, `roleCode`.
- Giới hạn kích thước JSON `metadata` tối đa **32 KB**.

---

## 5. Cơ chế Chuỗi Băm Mật mã (`Tamper-Evident Hash Chain`)

### 5.1. Thuật toán Ghi Chuỗi Băm dưới `pg_advisory_xact_lock`

Mỗi khi chèn một dòng vào `banking.audit_log`, `AuditHashChainWriter` thực hiện tuần tự:

1. **Lấy khóa giao dịch toàn cục cho chuỗi Audit:**
   ```sql
   SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0));
   ```
2. **Đọc mã băm của bản ghi cuối cùng hiện tại:**
   ```sql
   SELECT record_hash
   FROM banking.audit_log
   ORDER BY audit_id DESC
   LIMIT 1;
   ```
   - Nếu bảng `banking.audit_log` chưa có dòng nào (bản ghi đầu tiên), gán `prev_hash = "0000000000000000000000000000000000000000000000000000000000000000"` (`64` ký tự `'0'`).
3. **Chuẩn hóa Canonical Payload (`hash_version = 1` theo RFC 8785 JCS):**
   Tạo đối tượng JSON canonical với các khóa sắp xếp theo thứ tự bảng chữ cái:
   ```json
   {"action":"TRANSFER_COMPLETED","actorRoles":["CUSTOMER"],"actorType":"CUSTOMER","actorUserId":"u1000000-0000-0000-0000-000000000001","correlationId":"corr-123456","errorCode":null,"hashVersion":1,"metadata":{"amount":5000000,"currency":"VND","destAccountId":"a1000000-0000-0000-0000-000000000002","sourceAccountId":"a1000000-0000-0000-0000-000000000001","status":"COMPLETED"},"occurredAt":"2026-11-07T10:20:00.125Z","prevHash":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","requestId":"req-77889900","result":"SUCCESS","targetId":"TRF2026110784920134","targetType":"TRANSFER"}
   ```
   *(Lưu ý: `occurredAt` luôn được format chuẩn UTC với độ chính xác microsecond/millisecond cố định như đã lưu vào cột `occurred_at`).*
4. **Tính `record_hash`:**
   $$\text{record\_hash} = \text{HexLower}\left(\text{SHA-256}\left(\text{UTF8}(\text{JCS\_Payload}) \mathbin{\Vert} \text{"|"} \mathbin{\Vert} \text{prev\_hash}\right)\right)$$
5. **Thực hiện `INSERT INTO banking.audit_log ...`**.

### 5.2. Tiến trình Kiểm định Chuỗi Băm (`AuditChainVerificationScheduler`)

- Chạy định kỳ mỗi `15 phút` (hoặc kích hoạt theo yêu cầu kiểm toán).
- Đọc theo từng lô (`batch size = 1,000`) theo thứ tự `ORDER BY audit_id ASC`:
  - Kiểm tra bản ghi đầu tiên có `prev_hash == "0".repeat(64)`.
  - Với mọi bản ghi thứ $i > 1$: kiểm tra `row[i].prev_hash == row[i-1].record_hash`.
  - *(Lưu ý `DD-AUD-05`: `audit_id` có thể có khoảng trống số học do sequence tăng ở các transaction bị rollback, điều này hoàn toàn bình thường; việc kiểm tra tính liên tục dựa trên liên kết `row[i].prev_hash == row[i-1].record_hash` theo thứ tự `audit_id ASC`).*
  - Tính lại `expected_record_hash` từ dữ liệu lưu trong dòng $i$; nếu `expected_record_hash != row[i].record_hash` -> phát cảnh báo khẩn cấp `banking_audit_chain_tamper_detected_total` (`CRITICAL`).

---

## 6. Đặc tả Chi tiết API Endpoints

### 6.1. `API-AUD-01` — Tra cứu Nhật ký Kiểm toán (`GET /api/v1/audit`)

- **Quyền truy cập:** Nhân viên có quyền `AUDIT_READ` (`OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`).
  - **Lọc phạm vi theo Role:** `RISK_COMPLIANCE` và `ADMIN_SECURITY` được xem toàn bộ action; `OPERATIONS` được xem các action phục vụ vận hành/tra soát giao dịch và khách hàng.
- **Query Parameters:**
  - `correlationId` (exact match)
  - `actorUserId` (UUID)
  - `action` (exact match theo `AuditAction`)
  - `targetType` (`TRANSFER`, `ACCOUNT`, `USER`, `APPROVAL_REQUEST`, `FRAUD_FLAG`, ...)
  - `targetId` (VD: mã `reference` của giao dịch chuyển tiền)
  - `result` (`SUCCESS`, `FAILURE`, `REJECTED`)
  - `from`, `to` (ISO-8601 UTC, mặc định 7 ngày gần nhất, tối đa 90 ngày)
  - `page` (`0`), `size` (`20`, max `100`)
- **Lưu ý (`DD-AUD-11`):** Việc gọi `GET /api/v1/audit` và `GET /api/v1/audit/{auditId}` thành công là thao tác đọc thuần túy, **không** sinh thêm bản ghi trong `banking.audit_log` để tránh khuếch đại ghi (write amplification).

### 6.2. `API-AUD-02` — Xem Chi tiết Bản ghi Kiểm toán (`GET /api/v1/audit/{auditId}`)

- **Quyền truy cập:** `AUDIT_READ`.
- **Response `200 OK`:** Trả về toàn bộ thông tin bản ghi bao gồm `metadata`, `prevHash`, `recordHash`, `hashVersion`, `clientIp`, `userAgent` để phục vụ đối chiếu và xác minh độc lập.

### 6.3. `API-AUD-03` — Xuất Bằng chứng Kiểm toán (`GET /api/v1/audit/export`)

- **Quyền truy cập:** Chỉ nhân viên có quyền `AUDIT_EXPORT` (`RISK_COMPLIANCE`, `ADMIN_SECURITY`). Nếu `OPERATIONS` hoặc `CUSTOMER` gọi -> trả về `403 AUTH_FORBIDDEN` + ghi audit `SECURITY_ACCESS_DENIED`.
- **Query Parameters:** Giống bộ lọc của `GET /api/v1/audit` cộng thêm `format` (`CSV` hoặc `JSONL`, mặc định `CSV`).
- **Giới hạn an toàn:** Tối đa **`10,000` dòng** mỗi lần xuất. Nếu truy vấn vượt quá `10,000` dòng, trả về `400 VALIDATION_ERROR` yêu cầu thu hẹp khoảng thời gian `from`/`to`.
- **Luồng thực thi Fail-closed (`DD-AUD-14`):**
  1. Truy vấn danh sách bản ghi thỏa điều kiện lọc và kết xuất ra bộ đệm (hoặc tệp tạm), đồng thời tính mã băm `SHA-256` của toàn bộ nội dung tệp xuất (`fileHash`).
  2. Ghi bản ghi kiểm toán `action = 'AUDIT_EXPORTED'` (phân loại `C2`) chứa `rowCount`, `format`, `from`, `to`, `fileHash`. Nếu việc ghi bản ghi `AUDIT_EXPORTED` thất bại -> hủy kết xuất và trả về `500 AUDIT_WRITE_FAILED` (tuyệt đối không trả file khi chưa ghi nhận bằng chứng xuất!).
  3. Trả về nội dung tệp kèm các HTTP Response Headers:
     - `Content-Type`: `text/csv; charset=UTF-8` hoặc `application/x-ndjson; charset=UTF-8`
     - `Content-Disposition`: `attachment; filename="audit-export-20261107-103500.csv"`
     - `X-Export-SHA256`: `<64_hex_sha256_of_exported_file>`
     - `X-Export-Audit-Id`: `<audit_id_of_AUDIT_EXPORTED_event>`

---

## 7. Quản lý Lưu trữ & Giữ Pháp lý (`Retention & Legal Hold`)

1. **Chính sách Lưu trữ Mặc định (`banking.retention_policy`):**
   - `AUDIT_LOG`: Lưu trữ tối thiểu `1,825 ngày` (`5 năm`).
   - `LEDGER_ENTRY` & `TRANSFER`: Lưu trữ tối thiểu `1,825 ngày` (`5 năm`).
   - `IDEMPOTENCY_RECORD`: Tự động dọn dẹp sau khi hết hạn (`expires_at <= now()`, mặc định `24 giờ` — chỉ xóa các bản ghi không bị khóa bởi Legal Hold).
2. **Ưu tiên Giữ Pháp lý (`banking.legal_hold` — `I-AUD-06`):**
   - Bất kỳ tác vụ dọn dẹp/lưu trữ (`RetentionService`) nào cũng phải kiểm tra bảng `banking.legal_hold` (`WHERE status = 'ACTIVE'`).
   - Nếu một `CUSTOMER`, `ACCOUNT`, hoặc `TRANSFER` đang có lệnh `legal_hold` ở trạng thái `ACTIVE`, mọi bản ghi liên quan đến đối tượng đó **tuyệt đối không được xóa (purge)**.

---

## 8. Checklist Kiểm thử cho Developer & Coding Agent

- [ ] **Append-only Immutability (`T-AUD-02`):** Thử thực thi lệnh `UPDATE banking.audit_log SET result = 'FAILURE' WHERE audit_id = 1` hoặc `DELETE FROM banking.audit_log` bằng tài khoản ứng dụng -> bị PostgreSQL từ chối bởi cả quyền bảng và trigger `trg_audit_log_immutable` (`SQLSTATE 55000`).
- [ ] **Hash Chain Verification (`T-AUD-03`):** Thực hiện 10 giao dịch chuyển tiền và đăng nhập, sau đó chạy `AuditChainVerificationScheduler.verifyFullChain()` -> xác nhận `100%` bản ghi khớp `prev_hash` và `record_hash`.
- [ ] **Tamper Detection (`T-AUD-04`):** Trong môi trường test (dùng quyền superuser tạm tắt trigger), sửa 1 ký tự trong cột `metadata` của 1 dòng `audit_log`, sau đó chạy lại trình kiểm định -> phát hiện chính xác `audit_id` bị can thiệp.
- [ ] **End-to-End Correlation Trace (`AC-022`):** Gửi `POST /api/v1/transfers` kèm header `X-Correlation-ID: trace-demo-001` -> truy vấn `GET /api/v1/audit?correlationId=trace-demo-001` trả về đầy đủ chuỗi sự kiện `TRANSFER_CREATED` và `TRANSFER_COMPLETED` cùng mã correlation đó.
