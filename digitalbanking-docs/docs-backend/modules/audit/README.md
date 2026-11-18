# Module `audit` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-AUDIT-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai component/candidate (171 tests PASS — chờ ghép tích hợp `AuditModuleApi` & REST Controller)
> **Owner:** Thịnh
> **Phối hợp / Reviewer:** Phương (`common`, `ledger`), Đạt (`identity`, `admin`), Vũ (`transfer`, `limit`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-034..038, FR-053, AC-018..022, AC-034), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.6), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §8.5), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.6), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.1, §13.4), [[digitalbanking-docs/docs-backend/modules/_overview/07-audit.md]], [[DECISIONS.md]] (`DEC-COM-001/002`, `DEC-TRF-007`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.audit` cung cấp nhật ký kiểm toán nghiệp vụ bất biến (**Append-Only, Tamper-Evident Audit Trail**) với chuỗi băm mật mã liên tiếp (`SHA-256` trên dữ liệu chuẩn hóa RFC 8785 JCS) cho toàn bộ giao dịch tài chính (`C1`), thay đổi trạng thái nhạy cảm (`C2`), sự kiện bảo mật (`C3`) và sự kiện vận hành (`C4`). Module đồng thời cung cấp dịch vụ tra cứu, xuất dữ liệu kiểm toán (`CSV`/`JSONL`) và kiểm định tính toàn vẹn của chuỗi băm (`AuditChainVerifier`).

---

## 2. Hiện trạng Triển khai trong `digitalbanking-backend/src/main/java/com/simulatedbank/banking/audit`

Đã triển khai **16 classes Java** theo đúng kiến trúc Hexagonal (`00. architecture & conventions.md` §4.2) và đạt **171 tests PASS** trên PostgreSQL 16 Testcontainers:
- **`domain/` (7 classes):** `AuditActionCatalog`, `AuditEvent`, `AuditEventValidator`, `AuditSnapshotValues`, `AuditCanonicalJson` (ủy quyền RFC 8785 cho `common.crypto.JcsCanonicalizer`), `AuditHashing` (dùng `common.crypto.HmacService.sha256Hex`), `AuditSearchQuery`.
- **`application/` & `application/port/` (6 classes):** `AuditRecorder` (`@Transactional(propagation = MANDATORY)` cho `C1`/`C2`), `AuditQueryService`, `AuditDetailProjection` (kiểm soát allowlist 14 loại thực thể khi đọc), `AuditRecordRef`, `AuditRecordWriterPort`, `AuditQueryPort`.
- **`adapter/out/` (4 classes):** `AuditJdbcWriter` (kiểm tra `READ COMMITTED`, lấy khóa `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, chèn vào `banking.audit_log`), `AuditQueryJdbcStore`, `AuditQuerySql`, `AuditChainJdbcVerifier` (quét keyset theo lô 1.000 bản ghi).
- **`verification/` (1 class):** `AuditChainVerifier` (tái chuẩn hóa `canonical_payload` và đối chiếu khớp với từng cột lưu trữ).

---

## 3. Bảng Sở hữu & Hợp đồng Giao tiếp Nội bộ

- **Bảng sở hữu độc quyền (`Write-Owner`):** `banking.audit_log` (append-only, gồm cả cột `canonical_payload JSONB NOT NULL` và `client_info_masked JSONB`), `banking.legal_hold`, `banking.retention_policy`, `banking.audit_chain_checkpoint` (migration `V5__create_audit_log.sql` theo `DEC-COM-001`).
- **Hợp đồng giao tiếp nội bộ (`design/05` §11.1):**
  - `recordInTransaction(AuditEvent)` (`MANDATORY`): Ghi sự kiện `C1`/`C2` ở bước cuối cùng của giao dịch nghiệp vụ; lỗi ghi làm rollback toàn bộ giao dịch (`AC-034`, `DEC-TRF-007`).
  - `recordStandalone(AuditEvent)` (`REQUIRES_NEW`): Ghi sự kiện bảo mật `C3` độc lập; lỗi ghi kích hoạt `SecurityAuditFallbackLog` mà không làm thay đổi kết quả từ chối bảo mật.

---

## 4. Danh mục Tài liệu & Báo cáo Kiểm chứng trong `modules/audit/`

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [Tóm tắt Module 07: `audit`](../_overview/07-audit.md) | Tổng quan phạm vi, bảng dữ liệu, REST API `API-AUD-01..03` và Internal Ports. | Hoàn thành |
| [BE-AUD-01 — Audit Writer và Internal Port](BE-AUD-01-audit-writer.md) | Đặc tả danh mục hành động kiểm toán, chuẩn hóa JCS, giao thức chuỗi băm `SHA-256`, khóa `pg_advisory_xact_lock` và hành vi giao dịch `C1`/`C2`/`C3`. | Đã triển khai component |
| [BE-AUD-01 — Báo cáo Kiểm chứng](BE-AUD-01-verification-report.md) | Kết quả kiểm chứng 90 audit tests + 44 Common JCS tests + ArchUnit trên PostgreSQL 16 Testcontainers. | Component Verified |
| [BE-AUD-02 — Tra cứu, Xuất dữ liệu và Kiểm định Chuỗi băm](BE-AUD-02-query-export-verification.md) | Đặc tả truy vấn phân trang/lọc an toàn, `AuditDetailProjection`, luồng xuất `CSV`/`JSONL` có giới hạn và `AuditChainVerifier`. | Đã triển khai query/verifier |
| [BE-AUD-02 — Báo cáo Kiểm chứng](BE-AUD-02-verification-report.md) | Kết quả kiểm chứng 171 tests (bao gồm `AuditQueryJdbcStoreIT` và `AuditChainVerifierTest`). | Component Verified |
| [BE-AUD-03 — Lưu trữ (Retention), Giữ Pháp lý (Legal Hold) và Archival](BE-AUD-03-retention-legal-hold.md) | Thiết kế quy trình lưu trữ, kiểm tra `legal_hold`, tạo checkpoint và cổng an toàn trước khi dọn dẹp dữ liệu cũ. | Hoàn thành thiết kế |
| [BE-AUD-03 — Báo cáo Kiểm chứng](BE-AUD-03-verification-report.md) | Ma trận kiểm chứng `retention_policy` và `legal_hold` trên môi trường cô lập. | Planned |
