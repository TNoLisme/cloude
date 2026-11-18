# BE-AUD-03 — Báo cáo Kiểm chứng Lưu trữ & Giữ Pháp lý (`Retention & Legal Hold`)

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-AUDIT-03-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã lên kế hoạch (`Planned` — cổng an toàn chặn purge đang bật; chờ triển khai ở giai đoạn sau tích hợp core)
> **Đặc tả công việc:** [BE-AUD-03-retention-legal-hold.md](BE-AUD-03-retention-legal-hold.md)

---

## 1. Ma trận Kiểm chứng Dự kiến (`Verification Matrix`)

| Hạng mục kiểm tra | Thủ tục kiểm thử | Kết quả mong đợi | Trạng thái |
|---|---|---|---|
| Phân giải phạm vi `legal_hold` | Unit test với dữ liệu giả lập cho `CUSTOMER`, `ACCOUNT`, `TRANSFER`, `AUDIT_LOG` | Phát hiện chính xác mọi bản ghi nằm trong phạm vi đang có `legal_hold = ACTIVE` | **Planned** |
| Cổng an toàn chặn `RETENTION_PURGED` mặc định | `AuditEventValidatorTest` (đã có trong `BE-AUD-01`) | Hành động `RETENTION_PURGED` bị từ chối fail-closed khi quy trình purge chưa được kích hoạt | **Verified (Fail-closed Guard)** |
| Lỗi tạo bản lưu trữ / checkpoint | Testcontainers PostgreSQL + tiêm lỗi | Không xóa bất kỳ dòng nào nếu tệp lưu trữ hoặc `audit_chain_checkpoint` bị thiếu/hỏng | **Planned** |
| Đua đồng thời giữa đặt `legal_hold` và tiến trình `purge` | Hai giao dịch PostgreSQL độc lập + barrier | Lệnh `legal_hold` đang `ACTIVE` luôn chặn hoàn toàn thao tác xóa | **Planned** |
| Kiểm định chuỗi băm sau khi cắt tiền tố (`Checkpoint Continuity`) | Testcontainers PostgreSQL | `AuditChainVerifier` xác minh thành công phần chuỗi còn lại bắt đầu từ `through_record_hash` của checkpoint | **Planned** |

---

## 2. Ghi chú Vận hành

Trong phạm vi MVP hiện tại, bảng `banking.audit_log` giữ nguyên toàn bộ lịch sử (không chạy job xóa vật lý) và được bảo vệ bởi quyền `SELECT, INSERT` của `banking_app` cùng trigger chặn `UPDATE/DELETE/TRUNCATE` (`DEC-COM-002`).
