# BE-AUD-01 — Báo cáo Kiểm chứng Audit Writer

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-AUDIT-01-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã kiểm chứng thành phần (`Component Verified` — 139 tests PASS trên PostgreSQL 16 Testcontainers)
> **Đặc tả công việc:** [BE-AUD-01-audit-writer.md](BE-AUD-01-audit-writer.md)

---

## 1. Ma trận Kiểm chứng (`Verification Matrix`)

| Hạng mục kiểm tra | Lệnh thực thi | Kết quả mong đợi | Kết quả thực tế | Trạng thái |
|---|---|---|---|---|
| Kiểm tra domain & allowlist snapshot | `./gradlew test --tests '*AuditEventValidatorTest' --tests '*AuditSnapshotContractTest'` | Kiểm tra danh mục hành động, actor/outcome, allowlist theo thực thể, che dấu PII và quy tắc tiền tệ | **67 tests PASS** (40 validator + 27 snapshot contract) | **Verified** |
| Chuẩn hóa JCS & tính chuỗi băm `SHA-256` | `./gradlew test --tests '*AuditCanonicalJsonTest' --tests '*AuditHashingTest' --tests '*JcsCanonicalizerTest'` | Khớp vector RFC 8785 JCS, định dạng `canonical_payload` và công thức `record_hash` | **9 tests Audit + 44 tests Common JCS PASS** | **Verified** |
| Ghi `audit_log` trên PostgreSQL 16 & khóa chuỗi băm | `./gradlew test --tests '*AuditWriterIT'` | Áp dụng migration, kiểm tra `MANDATORY` TX, rollback theo caller, chặn `UPDATE/DELETE/TRUNCATE` với `banking_app`, độ chính xác microsecond UTC, kiểm tra `READ COMMITTED` | **14 tests PASS** trên container PostgreSQL 16.14 cô lập | **Verified** |
| Đua ghi chuỗi băm đồng thời (`Concurrent Chain Writers`) | Nằm trong `AuditWriterIT` | 8 luồng ghi đồng thời tạo thành 1 chuỗi băm liên tục không phân nhánh (`fork`), dữ liệu nghiệp vụ và audit commit nguyên tử | **PASS** | **Verified** |
| Kiểm tra ranh giới kiến trúc (`ArchUnit`) | `./gradlew test --tests '*ModuleBoundaryArchTest' --tests '*AuditBoundaryArchTest'` | Tầng `application` không phụ thuộc trực tiếp vào `adapter/out`; các module khác không import gói nội bộ của `audit` | **PASS** | **Verified** |
| Ghép nối caller thực tế (`identity`, `transfer`, `admin`, `fraud`) | Kiểm thử tích hợp liên module | Ghi nhận đầy đủ sự kiện `C1`/`C2` nguyên tử và `C3` standalone trên luồng nghiệp vụ thật | Chờ mở public port `AuditModuleApi` và ghép nối khi hợp nhất nhánh | **Chờ ghép tích hợp** |

---

## 2. Bằng chứng Kỹ thuật Đã Triển khai

1. **Chuẩn hóa Snapshot ngay khi khởi tạo (`AuditSnapshotValues`):**
   - Chuẩn hóa mọi định danh (`ID`) thành chuỗi số, số tiền (`Money`) thành chuỗi thập phân cố định 2 chữ số lẻ và bộ đếm thành số nguyên an toàn ngay khi tạo bản sao bất biến `AuditEvent`.
   - Đảm bảo cả đầu vào băm JCS và cột `JSONB` lưu trong PostgreSQL nhận cùng một biểu diễn chính xác, loại bỏ hoàn toàn sai lệch dấu phẩy động hoặc ký hiệu số mũ.
2. **Ủy quyền RFC 8785 JCS và `SHA-256` cho `common`:**
   - `AuditCanonicalJson` kiểm tra độ sâu và kiểu dữ liệu Java rồi gọi `common.crypto.JcsCanonicalizer`; `AuditHashing` gọi `common.crypto.HmacService.sha256Hex`.
3. **Bảo đảm tính tuần tự của chuỗi băm (`READ COMMITTED` + `pg_advisory_xact_lock`):**
   - `AuditJdbcWriter` kiểm tra giao dịch hiện tại đang ở mức cô lập `READ COMMITTED` trước khi lấy khóa `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, ngăn chặn hiện tượng chụp snapshot cũ (`REPEATABLE READ`) gây phân nhánh chuỗi băm khi có nhiều giao dịch chờ khóa.
4. **Tách bạch ranh giới Ports & Adapters (`BE-ARCH-00` §4.2):**
   - `AuditRecorder` (`application`) chỉ phụ thuộc vào outbound port `AuditRecordWriterPort` (`application/port`) và trả về `AuditRecordRef`. Toàn bộ câu lệnh SQL, `JdbcTemplate` và khóa advisory nằm trong `AuditJdbcWriter` (`adapter/out`).

---

## 3. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.5` | 11–14/11/2026 | Triển khai và kiểm chứng `AuditEventValidator`, `AuditCanonicalJson`, `AuditHashing` và `AuditWriterIT` (139 tests PASS). |
| `v0.6`–`v0.8` | 15/11/2026 | Tách `AuditRecordWriterPort`, `AuditRecordRef` (`application/port`) và `AuditJdbcWriter` (`adapter/out`) theo đúng chuẩn kiến trúc `BE-ARCH-00` §4.2. |
| `v1.0` | 18/11/2026 | Chuẩn hóa báo cáo kiểm chứng tiếng Việt và đồng bộ lộ trình ghép nối `DEC-COM-001`. |
