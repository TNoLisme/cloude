# BE-AUD-02 — Báo cáo Kiểm chứng Tra cứu & Kiểm định Chuỗi băm Audit

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-AUDIT-02-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã kiểm chứng thành phần (`Component Verified` — 171 tests PASS trên PostgreSQL 16 Testcontainers; chờ mở REST Controller & Export)
> **Đặc tả công việc:** [BE-AUD-02-query-export-verification.md](BE-AUD-02-query-export-verification.md)

---

## 1. Ma trận Kiểm chứng (`Verification Matrix`)

| Hạng mục kiểm tra | Thủ tục / Bài kiểm thử | Kết quả mong đợi | Kết quả thực tế | Trạng thái |
|---|---|---|---|---|
| Hợp đồng lọc & phân trang (`AuditSearchQuery`) | `AuditQueryContractTest` (6 tests), `AuditQuerySqlTest` (4 tests) | Điền mặc định `from`/`to` độc lập, giới hạn 366 ngày, phân trang 0-based `size` 1–100, whitelist sort `occurredAt,desc\|asc`, không dùng `SELECT *` | **10/10 tests PASS** | **Verified** |
| Bộ lọc bảo vệ khi đọc (`AuditDetailProjection`) | `AuditDetailProjectionTest` (26 tests) | Cưỡng chế allowlist trên 14 loại thực thể và `FRAUD_FLAG.matchedValue`, chặn số thực dấu phẩy động/số mũ, giới hạn raw JSONB `<= 64 KiB` (compact `<= 32 KiB`) và `<= 4096` nút (hỗ trợ tới 512 mã quyền) | **26/26 tests PASS** | **Verified** |
| Dịch vụ tra cứu & ánh xạ OpenAPI DTO (`AuditQueryService`) | `AuditQueryServiceTest` (9 tests) | Chỉ phụ thuộc vào `AuditQueryPort`, trả về `AuditPageView` và `AuditDetailView` phẳng theo `allOf: AuditSummary`, trả `404 AUDIT_NOT_FOUND` khi không tồn tại | **9/9 tests PASS** | **Verified** |
| Truy vấn & kiểm định trên PostgreSQL 16 (`AuditQueryJdbcStoreIT`) | `AuditQueryJdbcStoreIT` (16 tests) | Tie-break `(occurred_at, audit_id)`, kết hợp bộ lọc `AND` + `reference`, khoảng trống sequence sau rollback hợp lệ, `occurred_at` không đơn điệu hợp lệ, xóa dòng giữa chuỗi bị phát hiện `MISMATCH`, round-trip 512 roleCodes | **16/16 tests PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Kiểm định chuỗi băm (`AuditChainVerifier`) | `AuditChainVerifierTest` (10 tests) | Quét theo lô keyset tới `upperAuditId`, phát hiện sửa cột hiển thị mà không sửa payload, phát hiện sai `prev_hash`/`record_hash` hoặc `hash_version` lạ | **10/10 tests PASS** | **Verified** |
| Ranh giới kiến trúc Hexagonal (`ArchUnit`) | `ModuleBoundaryArchTest` (10 tests), `AuditBoundaryArchTest` (3 tests) | Tầng `application` không phụ thuộc `adapter/out`; các module khác không import gói nội bộ của `audit` | **13/13 rules PASS** | **Verified** |
| Xuất file `CSV`/`JSONL` & REST Controller (`API-AUD-01..03`) | Kiểm thử tích hợp HTTP với `IdentitySessionAuthenticator` thật | Phân quyền `AUDIT_READ` / `AUDIT_EXPORT`, trả `413 EXPORT_TOO_LARGE` khi vượt `maxRows`, commit `AUDIT_EXPORTED` (`C2`) trước khi gửi file | Chờ ghép nối REST Controller và `AuditExportService` | **Chờ ghép tích hợp** |

---

## 2. Kết quả Chạy Kiểm thử Tổng hợp

Lệnh tái lập toàn bộ bộ kiểm thử của module `audit` và ranh giới kiến trúc:

```bash
./gradlew test \
  --tests '*AuditQueryContractTest' \
  --tests '*AuditQuerySqlTest' \
  --tests '*AuditQueryJdbcStoreIT' \
  --tests '*AuditDetailProjectionTest' \
  --tests '*AuditQueryServiceTest' \
  --tests '*AuditChainVerifierTest' \
  --tests '*AuditWriterIT' \
  --tests '*AuditEventValidatorTest' \
  --tests '*AuditSnapshotContractTest' \
  --tests '*AuditCanonicalJsonTest' \
  --tests '*AuditHashingTest' \
  --tests '*ModuleBoundaryArchTest' \
  --tests '*AuditBoundaryArchTest'
```

- **Kết quả:** `BUILD SUCCESSFUL` — **171 tests PASS**, `0` thất bại, `0` lỗi, `0` bỏ qua.

---

## 3. Phân tích Thiết kế Persistence & Ranh giới Kiến trúc (`BE-ARCH-00` §3, §4, §6, §8)

| Thành phần | Quy ước áp dụng | Quyết định kỹ thuật | Bài kiểm thử bảo vệ |
|---|---|---|---|
| `AuditQueryPort` & `AuditRecordWriterPort` (`application.port`) | `BE-ARCH-00` §4.2 | Tách biệt hoàn toàn tầng `application` khỏi `JdbcTemplate` và câu lệnh SQL native; sử dụng các Java `record` thuần dữ liệu bất biến (`AuditRecordSummary`, `AuditRecordPage`, `AuditRecordDetail`, `AuditRecordRef`). | `AuditQueryServiceTest`, `AuditWriterIT`, `AuditBoundaryArchTest` |
| `AuditQueryJdbcStore`, `AuditQuerySql`, `AuditChainJdbcVerifier` (`adapter.out`) | `BE-ARCH-00` §4.2, `05. Technical stack.md` §4.3, `BE-CON-39/40` | Sử dụng `JdbcTemplate` với câu lệnh SQL tham số hóa và fixed projection cho bảng append-only `audit_log` (truy xuất trực tiếp các cột `JSONB` và quét keyset theo lô 1.000 dòng mà không gây tích tụ bộ nhớ đệm cấp 1 của JPA). | `AuditQueryJdbcStoreIT`, `AuditChainVerifierTest` |
| `AuditDetailProjection` (`application`) | `design/01` §5.6, `design/04` §7 | Xử lý hiện tượng PostgreSQL thêm khoảng trắng khi xuất `jsonb::text` bằng cách giới hạn văn bản thô `<= 64 KiB` trước khi parse và JSON nén `<= 32 KiB` sau khi parse, đồng thời cưỡng chế allowlist 14 loại thực thể và tối đa `4096` nút. | `AuditDetailProjectionTest`, `AuditQueryJdbcStoreIT` |
| `AuditQueryService` (`application`) | `BE-CON-14`, `BE-CON-46`, `07` §8.6 | Trải phẳng (`flatten`) các trường của `AuditSummary` lên cấp gốc của `AuditDetailView` (đánh dấu `@JsonIgnore` trên accessor phụ `summary()`) để khớp tuyệt đối với định nghĩa `allOf: AuditSummary` trong OpenAPI. | `AuditQueryServiceTest` |
