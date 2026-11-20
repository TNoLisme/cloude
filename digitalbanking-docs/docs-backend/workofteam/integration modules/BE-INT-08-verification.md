# BE-INT-08 — Kiểm chứng OTP và Notification T3

| Mục | Phạm vi |
|---|---|
| Trạng thái | **Verified** trong phạm vi BE-INT-08. Lượt cuối **129/129 PASS**, 0 failure/error/skip. |
| Ngày | 20/11/2026, Asia/Ho_Chi_Minh |
| Revision | `phuong/integration@02d92d3` + thay đổi workspace BE-INT-08. [Manifest nguồn](evidence/BE-INT-08/source-manifest.sha256) |
| Môi trường | JDK 21, Gradle 8.14.3, Docker 29.8.0, PostgreSQL Testcontainers `postgres:16-alpine`, role `banking_app`, Flyway `banking_migrator`. Gateway SMS ngoài tiến trình nghiệp vụ được mô phỏng bằng HTTP receiver loopback trong test. |
| Tiêu chí | FR-041–043, AC-003/025/033 và các điều kiện BE-INT-08 trong [kế hoạch tích hợp](README.md). |
| Tái lập | Chạy `sh "../digitalbanking-docs/docs-backend/workofteam/integration modules/evidence/BE-INT-08/verify.sh"` từ `digitalbanking-backend` với JDK 21 và Docker. Bộ lọc chỉ chọn luồng thay đổi và các consumer/migration liên quan theo DEC-INT-007. |
| Bằng chứng | [Kết quả theo từng lượt và test case](evidence/BE-INT-08/results.json). Bản trích từ JUnit chỉ giữ metadata và kết quả. Request SMS, OTP và địa chỉ không nằm trong artifact. |

Lượt hồi quy rộng có 302/304 ca đạt, gồm toàn bộ 49 ca Transfer T2. Hai ca sai kỳ vọng số migration V21 được cập nhật rồi chạy lại cùng bộ kiểm V22 và lease hiện hành. Lượt cuối đạt toàn bộ 129 ca được chọn. Các lỗi ở hai lượt đầu và lệnh chạy được giữ trong artifact.

## Ca nghiệm thu

| Ca | Kết quả quan sát |
|---|---|
| Login OTP → resend → verify | HTTP 202 sau ACK. Request quá sớm trả 429 kèm Retry-After. Đổi số Customer trước resend làm lần gửi mới đến số đang hiệu lực. Mã cũ bị từ chối, số lần đã thử được giữ và mã mới tạo session một lần. Audit C2 ghi bền cùng lần xoay mã. |
| Staff demo | Staff không có Customer row đăng nhập qua danh bạ SMS cấu hình ở profile test. Session giữ role Admin. |
| Transfer step-up → T2 → T3 | Gateway lỗi trả 503 và challenge bị hủy, không có transfer hoặc idempotency write. Retry cùng key nhận challenge mới, xác minh qua Identity rồi tạo transfer. T2 commit một outbox SMS cùng ledger. T3 gửi đến contact được đổi sau commit, giữ cùng key qua retry, branch thu hồi consent dừng gửi, và cả hai branch giữ kết quả ledger. |
| Lỗi OTP và concurrency | Kiểm reject/timeout/ACK không rõ, lỗi vô hiệu hóa và expiry sweep. Kiểm ACK cũ sau khi verifier đổi và sau khi challenge OTP hết hạn. OTP lỗi không tạo session hoặc chạy lại dispatch cùng generation. |
| Outbox và schema | PostgreSQL chứng minh khóa và claim nhiều worker, retry, token cũ, hết lease, rollback enqueue và schema canonical V1–V22. |

Các ca T2 dùng cấu hình tích hợp tường minh cùng provider thật. Worker được gọi chủ động trong các ca xác minh gửi; polling định kỳ và cảnh báo có cấu hình môi trường. Báo cáo chưa kiểm nhà cung cấp SMS triển khai thực tế, replay có Audit cho Ops, worker phục hồi giao dịch BE-INT-10 hoặc E2E trên trình duyệt.
