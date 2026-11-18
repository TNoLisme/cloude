# BE-AUD-02 — Đặc tả Tra cứu, Xuất dữ liệu và Kiểm định Chuỗi băm Audit

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-AUDIT-02` (`Task: BE-AUD-02`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai component tra cứu & kiểm định chuỗi băm (171 tests PASS — chờ ghép nối REST Controller & Export)
> **Owner:** Thịnh · **Reviewer:** Đạt (`identity`, `admin`), Phương (`common`, `ledger`), Vũ (`transfer`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.6), [[digitalbanking-docs/openapi/digital-banking-v1.yaml]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§7, §9, §13), [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-ID-001`)

---

## 1. Mục tiêu và Phạm vi

Thực hiện các yêu cầu `FR-036..038`, `AC-020..022`, `NFR-021`:
- Tra cứu danh sách và xem chi tiết bản ghi kiểm toán đã che dấu PII (`GET /api/v1/audit`, `GET /api/v1/audit/{auditId}`).
- Xuất dữ liệu bằng chứng kiểm toán ra định dạng `CSV` hoặc `JSONL` có giới hạn số dòng (`GET /api/v1/audit/export`).
- Kiểm định tính toàn vẹn của chuỗi băm mật mã trên bảng `banking.audit_log` (`AuditChainVerifier`).

Module tái sử dụng các thành phần sẵn có của `common`:
- `JcsCanonicalizer` và `HmacService.sha256Hex` để tái chuẩn hóa `canonical_payload` và kiểm tra mã băm.
- `RequestContextHolder` và `Clock` UTC để xử lý truy vết và khoảng thời gian lọc mặc định.
- `ErrorCode` (`AUDIT_NOT_FOUND` `404`, `EXPORT_TOO_LARGE` `413`, `AUTH_FORBIDDEN` `403`, `AUDIT_WRITE_FAILED` `500`, `VALIDATION_ERROR` `400`, `INTERNAL_ERROR` `500`, `SERVICE_UNAVAILABLE` `503`) và `ProblemWriter`.

---

## 2. Cấu trúc Thành phần theo Kiến trúc Hexagonal (`BE-ARCH-00` §4.2)

| Thành phần | Tầng / Package | Trách nhiệm |
|---|---|---|
| `AuditSearchQuery` | `audit.domain` | Value object thuần Java biểu diễn và kiểm tra điều kiện lọc: khoảng thời gian tối đa 366 ngày (`from` inclusive mặc định `now - 30d`, `to` exclusive mặc định `now`), phân trang 0-based (`size` 1–100, mặc định 20), whitelist sắp xếp `occurredAt,desc\|asc`. |
| `AuditQueryPort` | `audit.application.port` | Outbound port định nghĩa hợp đồng đọc `search(AuditSearchQuery, Instant)` và `findDetail(long)` với các record dữ liệu bất biến. |
| `AuditDetailProjection` | `audit.application` | Bộ lọc bảo vệ khi đọc (`read-time defense-in-depth`): kiểm tra allowlist trên 14 loại thực thể và cấu trúc lồng `FRAUD_FLAG.matchedValue` (`amount`, `count`, `window`), bảo vệ độ chính xác số học, giới hạn kích thước JSONB text `<= 64 KiB` (compact `<= 32 KiB`) và tối đa `4096` nút (hỗ trợ tới 512 mã quyền trên `APP_USER`). |
| `AuditQueryService` | `audit.application` | Dịch vụ điều phối tra cứu, chỉ phụ thuộc vào `AuditQueryPort`, áp dụng `AuditDetailProjection` và ánh xạ sang các record view khớp hoàn toàn schema OpenAPI (`AuditPageView`, `AuditSummaryView`, `AuditDetailView`, `PageMetaView`). |
| `AuditQueryJdbcStore` & `AuditQuerySql` | `audit.adapter.out` | Hiện thực `AuditQueryPort` bằng truy vấn SQL tham số hóa, danh sách cột tường minh (không dùng `SELECT *`, truy vấn danh sách không đọc cột snapshot lớn) và thứ tự sắp xếp tất định `(occurred_at, audit_id)`. |
| `AuditChainVerifier` | `audit.verification` | Tái chuẩn hóa `canonical_payload` qua `JcsCanonicalizer`, đối chiếu từng trường trong payload với các cột tương ứng của dòng DB, kiểm tra `hash_version`, `prev_hash` và `record_hash`. |
| `AuditChainJdbcVerifier` | `audit.adapter.out` | Quét tuần tự bảng `banking.audit_log` theo lô keyset (`audit_id ASC`, mặc định `1.000` bản ghi/lô) tới mốc `upperAuditId` mà không làm tràn bộ nhớ đệm. |
| `AuditExportService` & `AuditController` | `audit.application` / `audit.adapter.in` | Thực thi xuất file `CSV`/`JSONL` có giới hạn (`maxRows <= 10.000`) và mở các endpoint REST sau khi ghép nối adapter phân quyền. |

---

## 3. Quy tắc Tra cứu và Kiểm định Chuỗi băm (`Data & Hash Verification`)

### 3.1. Kiểm định chuỗi băm (`AuditChainVerifier` & `AuditChainJdbcVerifier`)
- Không bao giờ băm trực tiếp chuỗi `jsonb::text` do PostgreSQL trả về (vì PostgreSQL có thể thay đổi khoảng trắng hoặc thứ tự khóa trong `JSONB`). Thay vào đó, đọc `canonical_payload`, phân tích cú pháp và tái chuẩn hóa qua `common.crypto.JcsCanonicalizer`, sau đó tính `SHA-256` cùng `prev_hash`.
- Kiểm tra đồng thời:
  1. `hash_version` được hỗ trợ (`version = 1`) và khớp giữa cột DB với trường trong `canonical_payload`.
  2. Mã băm tính lại khớp tuyệt đối với cột `record_hash`.
  3. Cột `prev_hash` khớp với `record_hash` của bản ghi hợp lệ liền trước theo thứ tự `audit_id ASC` (hoặc bằng 64 ký tự `'0'` đối với bản ghi đầu tiên). Khoảng trống `audit_id` do giao dịch bị rollback là hợp lệ, nhưng việc xóa mất một bản ghi đã commit ở giữa chuỗi sẽ làm đứt liên kết `prev_hash` và bị phát hiện ngay lập tức.
  4. Toàn bộ 15 trường dữ liệu trong `canonical_payload` khớp hoàn toàn với các cột tương ứng trên bảng `banking.audit_log` (phát hiện cả trường hợp sửa trực tiếp cột hiển thị mà không sửa `canonical_payload`).

### 3.2. Quy tắc tra cứu (`AuditSearchQuery` & `AuditQueryService`)
- Mọi bộ lọc kết hợp theo phép `AND`; tham số `reference` ánh xạ vào `entity_type = 'TRANSFER' AND entity_id = :reference`.
- Thứ tự sắp xếp mặc định là `occurredAt,desc` (hỗ trợ cả `occurredAt,asc`), kèm khóa phụ `audit_id` cùng chiều để đảm bảo thứ tự trang ổn định khi có nhiều sự kiện trùng mốc thời gian.
- Thao tác đọc tra cứu không ghi thêm bản ghi vào `audit_log`.

---

## 4. Hợp đồng REST API, Phân quyền RBAC và Luồng Xuất Dữ liệu (`Export Flow`)

### 4.1. Phân quyền theo `07` §3.4 & §8.6 (`DEC-ID-001`)
- `GET /api/v1/audit` (`API-AUD-01`) và `GET /api/v1/audit/{auditId}` (`API-AUD-02`): yêu cầu quyền **`AUDIT_READ`** (cấp cho các role `OPERATIONS`, `ADMIN`, `AUDITOR`).
- `GET /api/v1/audit/export` (`API-AUD-03`): yêu cầu quyền **`AUDIT_EXPORT`** (chỉ cấp duy nhất cho role **`AUDITOR`**; `ADMIN` không có quyền xuất dữ liệu kiểm toán).

### 4.2. Quy trình xuất dữ liệu an toàn (`API-AUD-03`)
1. Kiểm tra quyền `AUDIT_EXPORT` và tính hợp lệ của bộ lọc (`maxRows` từ `1` đến `10.000`, định dạng `CSV` hoặc `JSONL`).
2. Chốt mốc `upperAuditId` đã commit tại thời điểm bắt đầu và đọc theo lô keyset tối đa `1.000` dòng với điều kiện `audit_id <= upperAuditId`.
3. Đọc thăm dò tối đa `maxRows + 1` dòng: nếu xuất hiện dòng thứ `maxRows + 1`, hủy ngay tệp tạm và trả về **`413 EXPORT_TOO_LARGE`** (tuyệt đối không cắt bớt dữ liệu âm thầm).
4. Ghi dữ liệu ra tệp tạm riêng biệt ngoài thư mục web tĩnh, tính mã băm `SHA-256` trên toàn bộ byte của tệp và đóng giao dịch đọc (không giữ khóa advisory toàn cục trong lúc tạo tệp).
5. Mở giao dịch ngắn `READ COMMITTED`, gọi `AuditRecorder.recordInTransaction` để ghi và **commit thành công** sự kiện `AUDIT_EXPORTED` (`C2`) trước khi gửi bất kỳ byte dữ liệu nào của tệp về client. Nếu ghi audit `C2` thất bại, xóa tệp tạm và trả về `500 AUDIT_WRITE_FAILED`.

---

## 5. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.3` | 11–14/11/2026 | Khởi tạo đặc tả tra cứu, xuất dữ liệu và kiểm định chuỗi băm; đồng bộ với `common` và OpenAPI. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt, cập nhật kiến trúc `AuditQueryPort` / `AuditDetailProjection` / `AuditChainJdbcVerifier` và đồng bộ phân quyền `DEC-ID-001`. |
