# BE-INT-10 — Phục hồi Transfer PENDING và Tfail

**Verified** trên PostgreSQL 16.15 với V24. Theo T-REC-01–04, T-CREDIT-FENCE-01, T-CREDIT-MAP-01 và DEC-INT-019. [Modules contract §8.5](../../../docs-common/design/05.%20Modules%20contract.md#85-be-int-10--evidence-và-tfail-dec-int-019)

| Hạng mục | Triển khai |
|---|---|
| Claim và xử lý | Dùng metadata V13. Discovery đọc primary, claim ngắn với Limit try-lock customer rồi Transfer `FOR UPDATE SKIP LOCKED`. Commit claim trước khi mở transaction xử lý. Kiểm lại PENDING, context, epoch/token và lease trước xử lý. CAS terminal giữ fence; ứng viên bận không tốn attempt. |
| Resume T2 | Basis T1 V24, Account/Ledger/Audit vắng sau truy vấn thành công, Limit xác nhận reservation PENDING chưa có effect và idempotency chưa có response. Executor T2 tham gia transaction đang giữ guard và fence. |
| Đối soát COMPLETED | Đối chiếu Account movement, đúng hai Ledger entries, Limit SETTLED theo cùng instant/scope và Audit completion C1. Dùng lại bản 201 gốc đã mask. Cập nhật Transfer, idempotency và C1 `TRANSFER_RECONCILED` cùng commit. |
| MANUAL | Chứng cứ thiếu/lệch, commit chưa xác định hoặc fence hết hạn dẫn đến cảnh báo. Giữ nguyên trạng thái nghiệp vụ và reservation. Nếu primary chưa nhận ghi, phát alert và tiếp tục giữ claim để chặn writer. |
| Tfail | Giữ nhánh savepoint trong T2. Khi lỗi credit sau debit làm abort toàn T2, callback rollback xác nhận mới cho phép ghi `FAILURE_PENDING` dưới fence còn hạn. Tfail đọc lại proof, release reservation rồi ghi FAILED/422, outbox và Audit C1 nguyên tử. Marker bền cho phép worker mới tiếp tục cùng fence sau restart. |
| Schema và vận hành | V24 thêm basis và Account witness append-only. Scheduler ở composition T2 chạy startup và mỗi 60 giây khi bật `app.transfer.recovery.enabled=true`. Prometheus dùng `transfer_recovery_incident_total`; log allowlist có reason, transfer ID và trace. [R-CON-01](../../../docs-common/design/02.%20consistency-concurrency.md#104-runbook-vận-hành) |

Evidence V20 hiện hỗ trợ tự sửa khi đã SETTLED và usage proof khớp. PENDING có hiệu ứng tiền nhưng thiếu settlement hoặc receipt gốc thuộc MANUAL. Intent trước V24 giữ basis NULL. Quy trình Ops rearm có phân quyền và audit thuộc phần vận hành riêng.

[Báo cáo kiểm chứng](BE-INT-10-verification.md)
