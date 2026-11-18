# BE-AUD-03 — Đặc tả Lưu trữ (`Retention`), Giữ Pháp lý (`Legal Hold`) và Archival

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-AUDIT-03` (`Task: BE-AUD-03`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành thiết kế — chờ triển khai sau khi ghép nối `admin` và `audit`
> **Owner:** Thịnh · **Reviewer:** Đạt (`admin`, `identity`), Phương (`common`, `ledger`), Vũ (`transfer`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§10), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.6), [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[DECISIONS.md]] (`DEC-COM-001`)

---

## 1. Mục tiêu và Cổng An toàn (`Safety Gate`)

Đặc tả cơ chế quản lý chính sách thời hạn lưu trữ (`banking.retention_policy`), lệnh giữ pháp lý (`banking.legal_hold`), mốc kiểm định chuỗi băm (`banking.audit_chain_checkpoint`) và quy trình lưu trữ/dọn dẹp dữ liệu hết hạn:
- **Cổng an toàn bắt buộc:** Tính năng xóa dữ liệu hết hạn (`purge`) được **tắt mặc định** trên các môi trường `local`, `demo` và `production` trong giai đoạn MVP. Mọi bài kiểm thử dọn dẹp dữ liệu chỉ được phép thực thi trên container PostgreSQL dùng một lần (`Testcontainers`).
- **Ưu tiên tuyệt đối của Legal Hold (`I-AUD-06`):** Không một bản ghi nào thuộc phạm vi đang có `legal_hold` ở trạng thái `ACTIVE` được phép bị xóa hoặc di chuyển làm mất khả năng truy xuất.

---

## 2. Phân định Quyền Sở hữu và Tái sử dụng `common`

- Module `audit` sở hữu độc quyền các bảng `audit_log`, `audit_chain_checkpoint`, `legal_hold` và `retention_policy` (migration `V5` theo `DEC-COM-001`).
- Module `admin` sở hữu bảng `approval_request` và điều phối luồng phê duyệt Maker-Checker khi đặt hoặc giải phóng lệnh giữ pháp lý (`LEGAL_HOLD_PLACED`, `LEGAL_HOLD_RELEASED`).
- Tái sử dụng `AppProperties` (`app.jobs.retention-cleanup-cron`), `JcsCanonicalizer`, `HmacService`, `RequestContextHolder` và `ErrorCode.LEGAL_HOLD_ACTIVE` (`409`) từ module `common`.

---

## 3. Quy trình Lưu trữ và Dọn dẹp An toàn (`10 Bước`)

1. Đọc cấu hình `retention_policy` đang bật (mặc định tối thiểu `5 năm` / `1.825 ngày` cho `AUDIT_LOG`, `TRANSFER`, `LEDGER_ENTRY`).
2. Phân giải phạm vi dữ liệu qua các port của module sở hữu; nếu việc phân giải gặp lỗi, dừng ngay tiến trình (`fail-closed`).
3. Kiểm tra các lệnh `legal_hold` đang `ACTIVE` dưới khóa phạm vi tương ứng; nếu trùng phạm vi giữ pháp lý, từ chối với `409 LEGAL_HOLD_ACTIVE`.
4. Xuất lưu trữ (`archive`) đoạn tiền tố liên tục (`contiguous prefix`) của chuỗi `audit_log` kèm tệp manifest chứa mã băm `SHA-256`.
5. Đọc lại và kiểm định độc lập tính toàn vẹn của gói lưu trữ vừa tạo.
6. Ghi nhận mốc kiểm định bất biến (`audit_chain_checkpoint` với `through_audit_id` và `through_record_hash`) để bảo toàn gốc kiểm định cho phần chuỗi còn lại.
7. Kiểm tra lại trạng thái `legal_hold` và phê duyệt bên trong một giao dịch DB ngắn.
8. Thực hiện dọn dẹp các dòng tiền tố đã lưu trữ hợp lệ và ghi sự kiện kiểm toán `RETENTION_PURGED` (`C2`) thông qua `AuditRecorder`.
9. Chạy `AuditChainVerifier` kiểm định lại toàn bộ phần chuỗi còn lại tính từ `audit_chain_checkpoint` vừa tạo.
10. Nếu phát hiện bất kỳ sai lệch nào ở bất kỳ bước nào: dừng ngay tiến trình, phát cảnh báo `CRITICAL` (`R-AUD-02` / `R-AUD-04`), giữ nguyên toàn bộ bằng chứng và tuyệt đối không bao giờ đặt lại genesis hay ghi đè chuỗi băm.

---

## 4. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.3` | 11–12/11/2026 | Khởi tạo thiết kế an toàn cho `retention_policy`, `legal_hold` và `audit_chain_checkpoint`. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt và đồng bộ chuỗi migration `DEC-COM-001`. |
