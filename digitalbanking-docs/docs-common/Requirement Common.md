# Requirement Common — Đề bài và Yêu cầu Trọng yếu Ban đầu

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `REQ-COMMON-00`
> **Phiên bản:** 1.0
> **Ngày:** 03/11/2026 (Chuẩn hoá: 18/11/2026)
> **Trạng thái:** Tham khảo gốc (Đã cụ thể hoá toàn bộ vào `02. BRD.md` và `03. SRS.md`)
> **Owner:** Product Owner / Business Analyst

---

## 1. Yêu cầu Kỹ thuật & Nghiệp vụ Trọng yếu (`Critical Requirements`)

- **Strong transactional consistency:** Đảm bảo tính nhất quán giao dịch tài chính mạnh mẽ (ACID, bút toán sổ cái kép cân bằng, không phát sinh số dư âm hoặc ghi nợ đơn lẻ).
- **Idempotent transfer operations:** Mọi thao tác chuyển tiền đều đảm bảo tính lũy đẳng (`Idempotency-Key`), chống ghi nợ lặp khi mạng chập chờn hoặc client gửi trùng yêu cầu.
- **Authentication + strong authorization:** Xác thực đa yếu tố (`MFA` / `Step-up OTP` / `FIDO2`), quản lý phiên máy chủ an toàn (`Opaque Session` + `CSRF`) và phân quyền chặt chẽ (`RBAC`, cô lập quyền sở hữu `Anti-IDOR`, phê duyệt 4 mắt `Maker-Checker`).
- **Immutable / auditable history:** Sổ cái (`ledger_entry`) và nhật ký kiểm toán (`audit_log`) chỉ cho phép ghi thêm (`append-only`), bảo vệ bằng chuỗi băm `SHA-256` tuần tự.
- **Encryption in transit & at rest:** Bảo mật đường truyền bằng `TLS 1.2/1.3` và mã hóa dữ liệu định danh nhạy cảm ở trạng thái nghỉ bằng `AES-256-GCM` kết hợp chỉ mục mù `HMAC-SHA256`.
- **High traceability:** Khả năng truy vết xuyên suốt mọi yêu cầu và giao dịch qua `X-Correlation-ID` và `X-Request-ID`.

## 2. Các Kịch bản Lỗi Bắt buộc Xử lý (`Failure Scenarios`)

```text
Debit succeeds     -> Credit fails          (Rollback nguyên tử T2, không bao giờ trừ tiền đơn lẻ)
Client timeout     -> Request is retried    (Phát lại kết quả qua Idempotency-Key hoặc trả 409 kèm Retry-After)
Service crashes    -> During transaction    (Tự động phát hiện và đối soát/phục hồi an toàn qua TransferRecoveryJob)
```

## 3. Tính năng Dữ liệu / Phát hiện Bất thường (`Anomaly / Fraud Detection`)

- Hệ thống đánh giá gian lận dựa trên tập quy tắc cấu hình được (`LARGE_VALUE`, `HIGH_FREQUENCY`, `UNUSUAL_PATTERN`) ngay trong luồng giao dịch chuyển tiền (`T2`), tự động sinh cờ cảnh báo (`fraud_flag`) để bộ phận Vận hành (`Operations`) thẩm định.

## 4. Các Kịch bản Trình diễn Nghiệm thu Bắt buộc (`Required Demonstrations D1–D5`)

1. **`D1` — Concurrent transfer test:** Kiểm thử chuyển tiền đồng thời cùng tài khoản nguồn hoặc chuyển chéo `A ↔ B` không gây số dư âm hoặc deadlock.
2. **`D2` — Duplicate request protection:** Bảo vệ chống gửi trùng yêu cầu chuyển tiền với cùng `Idempotency-Key`.
3. **`D3` — Failure / recovery scenario:** Kịch bản lỗi giữa chừng (ghi có lỗi sau ghi nợ, crash tiến trình) và cơ chế tự khôi phục/đối soát.
4. **`D4` — Security & audit review:** Kiểm chứng chống IDOR, RBAC, Maker-Checker, che dấu PII và kiểm định tính toàn vẹn chuỗi băm `audit_log`.
5. **`D5` — Load test with latency / error metrics:** Kiểm thử tải (`20 RPS` trong `10 phút`) đi kèm các chỉ số độ trễ (`p95`, `p99`), tỷ lệ lỗi và đối soát số dư sau tải.
