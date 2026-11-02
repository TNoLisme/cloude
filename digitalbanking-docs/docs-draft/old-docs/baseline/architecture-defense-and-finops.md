# Architecture Defense & FinOps Master Guide (9.5+ Grade Strategy)

> **Mục đích tài liệu:** Chuẩn bị trọn vẹn câu trả lời kỹ thuật cho **12 câu hỏi bảo vệ kiến trúc (Architecture Defense)** tại Slide 26 môn Cloud Application Development, chứng minh tư duy thiết kế, trade-offs, FinOps và độ tin cậy của hệ thống Digital Banking Simulator.

---

## 1. What business problem are you solving? (Bài toán nghiệp vụ)

* **Vấn đề cốt lõi:** Các hệ thống tài chính/ngân hàng đòi hỏi tính nhất quán tuyệt đối (Strong Transactional Consistency), khả năng chống trùng lặp request (Idempotency), bảo mật phân quyền nghiêm ngặt và khả năng kiểm toán bất biến (Audit Trail).
* **Phạm vi giải quyết (Vertical Slice):** Hệ thống mô phỏng một vòng đời tài chính hoàn chỉnh:
  1. Khách hàng tự đăng ký bằng SĐT + email (xác thực OTP SMS), hoặc nhân viên ngân hàng (Operator) mở hộ tại quầy; hệ thống tạo ngay tài khoản mặc định `ACTIVE`. Đăng nhập bằng SĐT, bắt buộc thiết lập PIN lần đầu; quên mật khẩu khôi phục qua OTP SMS hoặc Email.
  2. Operator cấp số dư mở tài khoản ban đầu (Demo Seed Balance).
  3. Khách hàng thực hiện chuyển tiền nội bộ thời gian thực, xác thực bằng PIN (giao dịch > 5,000,000 VNĐ cần thêm OTP SMS), với cơ chế bảo vệ giao dịch, chống overdraft và chống replay.
  4. Hệ thống tự động ghi vết kiểm toán (Audit Trail) và đánh giá rủi ro giao dịch bất thường (Suspicious Transaction Rules).

---

## 2. What workload assumptions did you make? (Giả định tải và hành vi)

* **Hành vi người dùng:**
  - Tỉ lệ đọc/ghi: Đọc nhiều hơn ghi (Read:Write ~ 7:3). Người dùng thường xuyên xem số dư và lịch sử trước khi thực hiện chuyển tiền.
  - Concurrency: Giả định 50 người dùng đồng thời (Concurrent Simulated Users) tại thời điểm cao điểm lớp học.
  - Throughput: Tải ổn định 20 RPS (Requests Per Second) trong 10 phút; Peak Burst lên đến 50 RPS.
* **Đặc tính tài chính:**
  - Quy mô tiền tệ: Giả định đơn vị VNĐ (mã tiền tệ: `VND`), độ chính xác số nguyên (Scale 0).
  - Giới hạn chuyển tiền: Tối thiểu 2,000 VNĐ / giao dịch chuyển khoản; tối đa 10,000,000 VNĐ / giao dịch chuyển khoản; tối đa 100,000,000 VNĐ cho lệnh seed balance (tối thiểu > 0).

---

## 3. What are your measurable NFRs? (Chỉ số phi chức năng định lượng)

Hệ thống đặt ra các chỉ số Service Level Objective (SLO) có thể đo lường trực tiếp qua load test:
* **Availability:** Proposed target >= 99.5% valid requests in documented load test. Count expected business 4xx separately from unexpected 5xx; report numerator, denominator and exclusions. This is a test target, not production SLO.
* **Latency:** MVP acceptance target: transfer and balance p95 <= 500 ms at 20 RPS under documented test conditions. Stretch goal: transfer p95 <= 350 ms after baseline measurement. Read p95 <= 150 ms is a future optimization target, not an MVP gate.
* **Consistency & Financial Invariants:** $100\%$ tính toàn vẹn (Zero dirty read, zero lost update, số dư tài khoản không bao giờ âm dưới mọi điều kiện tranh chấp).
* **Idempotency Accuracy:** $100\%$ deduplication (100 lần retry cùng key chỉ thực hiện 1 lần trừ/cộng tiền duy nhất).

---

## 4. Why did you choose this architecture? (Lý do chọn Modular Monolith)

* **Quyết định:** Chọn **Modular Monolith** (1 deployable Spring Boot, 1 PostgreSQL database, phân chia package theo bounded contexts độc lập: `identity`, `customer`, `account`, `transfer`, `audit`, `risk`, `shared`).
* **Biện minh kỹ thuật & Trade-offs:**
  1. *Tránh Over-engineering:* Việc chia ngay thành 6-7 microservices cho MVP (như repo tham khảo `digital-banking-platform`) làm tăng độ phức tạp hạ tầng không cần thiết (network latency, distributed transaction, partial failure, complex local dev).
  2. *Bảo đảm ACID mạnh nhất:* Tiền tệ nội bộ cần tính nhất quán tức thì (Strong Consistency). Trong Monolith, một giao dịch chuyển tiền được gói gọn trong 1 Local Database Transaction với `PESSIMISTIC_WRITE` lock, loại bỏ hoàn toàn rủi ro Distributed Split-Brain.
  3. *Domain isolation:* Module ownership boundaries help future extraction, but service splitting still requires deliberate data and API changes. It is not automatic.

---

## 5. Why this database / storage model? (Lý do chọn PostgreSQL)

* **Lý do chọn PostgreSQL:**
  - Tuân thủ tiêu chuẩn ACID nghiêm ngặt với cơ chế Multi-Version Concurrency Control (MVCC).
  - Hỗ trợ câu lệnh khóa dòng `SELECT ... FOR UPDATE` xác định và các ràng buộc toàn vẹn mạnh (`CHECK balance >= 0`, `UNIQUE(idempotency_key)`).
  - PostgreSQL `NUMERIC(19,0)` and Java `BigDecimal` preserve exact integer VND amounts (scale 0).
* **Mô hình dữ liệu:**
  - Bảng `accounts`: Lưu trạng thái hiện tại (Projection) để truy vấn tức thì.
  - Bảng `transfers`: Lưu lịch sử giao dịch bất biến (Immutable Financial Record).
  - Bảng `idempotency_keys`: Lưu hash SHA-256 payload và kết quả phản hồi để replay an toàn.

* **Tương lai:** Nếu có async consumer cụ thể, đánh giá Transactional Outbox và delivery/retry/monitoring path. Đây không phải MVP requirement.

---

## 6. What happens at 5× or 10× load? (Chiến lược scale khi tải tăng vọt)

* **Tải hiện tại (20 RPS):** 0.5 vCPU/1 GB RAM và `db.t4g.micro` là sizing giả định cần đo. Không khẳng định CPU hoặc capacity trước load test.
* **Khi tải tăng 5× (100 RPS):** Đo trước; tối ưu index/query, HikariCP, transaction duration và lock waits; scale stateless backend nếu CPU/memory là bottleneck. Read replica chỉ là future option cho read path chấp nhận replication lag. Giữ account balance và post-transfer history trên primary để bảo đảm read-your-writes.
* **Khi tải tăng 10× (200+ RPS):** Đo bottleneck trước. Có thể scale database/query hoặc tách service khi module boundaries và workload chứng minh cần thiết. Saga/queue/Kafka chỉ là lựa chọn tương lai; Saga chấp nhận trạng thái tạm thời không nhất quán và cần idempotent commands, durable orchestration, timeout, retry, compensation và reconciliation. Không thay thế ACID guarantee của MVP.

---

## 7. What happens when a dependency fails? (Ứng phó sự cố phụ thuộc)

1. **Database connection pool cạn kiệt hoặc DB gặp sự cố:**
   - Hệ thống áp dụng nguyên tắc **Fail-Closed**: Từ chối tiếp nhận giao dịch tiền tệ với lỗi `503 SERVICE_UNAVAILABLE`, tuyệt đối không ghi nhận trạng thái thành công ảo.
   - Endpoint `/health` trả về `503`, báo cho Load Balancer ngắt traffic.
2. **Client gặp lỗi mạng (Network Timeout) ngay sau khi Server commit DB:**
   - Client không biết giao dịch thành hay bại. Client gửi lại request với cùng `Idempotency-Key`.
   - Backend phát hiện key đã commit, lập tức trả về kết quả cũ kèm header `Idempotency-Replayed: true`, không trừ tiền lần 2.
  - A debit statement followed by credit failure rolls back the same PostgreSQL transaction. This is not a committed debit followed by a distributed compensation.

---

## 8. How do you deploy and rollback? (Quy trình triển khai & Rollback)

* **Triển khai (CI/CD via GitHub Actions):**
  1. Linting & Validation hợp đồng `openapi.yaml`.
  2. Chạy targeted unit tests & integration tests (với Testcontainers PostgreSQL).
  3. Build Docker container image bất biến và đẩy lên Container Registry (ECR / DockerHub).
  4. Chạy database migration tự động bằng Flyway theo chiến lược **Expand/Contract** (chỉ thêm cột/bảng mới, không xóa cột cũ gây break ứng dụng đang chạy).
  5. Cập nhật task definition / service trên Cloud container runtime (ECS Fargate / Cloud Run).
* **Chiến lược Rollback:**
  - Nếu phiên bản mới gặp sự cố: Rollback container image về tag trước đó qua blue/green hoặc rolling update (mất < 30 giây).
  - Vì schema migration áp dụng chiến lược backward-compatible (không drop column), phiên bản cũ vẫn tương thích hoàn toàn với database hiện tại mà không cần rollback data.

---

## 9. How do you protect data and access? (Bảo mật dữ liệu & Kiểm soát truy cập)

Áp dụng mô hình **Defense in Depth** dựa trên tiêu chuẩn STRIDE:
* **Authentication & session:** JWT access token stays in browser memory. Use baseline expiry 15 minutes pending implementation confirmation. Refresh token uses `HttpOnly`, `Secure` on HTTPS and `SameSite=Lax`, with `X-CSRF-Token` protection.
* **Phân quyền đa tầng (RBAC & IDOR):**
  - Role-Based: `@PreAuthorize("hasRole('OPERATOR')")` cho các hành động duyệt đơn/nạp tiền.
  - Ownership Check: Chặn tấn công IDOR bằng cách kiểm tra quyền sở hữu tại mức Service (`account.getCustomerId().equals(currentCustomer.getId())`), nếu không thuộc quyền sở hữu sẽ trả về `404` để che giấu sự tồn tại của resource.
* **Bảo vệ dữ liệu nhạy cảm:**
  - Mã hóa at-rest, TLS version, password-hash algorithm/cost và các dịch vụ cloud cụ thể phụ thuộc provider/security review; không xem AES-256, TLS 1.3 hoặc BCrypt cost 12 là giá trị đã kiểm chứng.
  - Che giấu dữ liệu (Masking): Mask số tài khoản (`••••4821`), không ghi token/mật khẩu ra log.

---

## 10. How do you know the system is healthy? (Giám sát & Khả năng quan sát)

Hệ thống tích hợp 3 trụ cột Observability:
1. **Health Check Endpoints:**
   - `GET /api/v1/health`: Kiểm tra liveness và readiness (trạng thái kết nối DB, dung lượng bộ nhớ).
2. **Structured Logging với Request Tracing:**
   - Mọi request được gắn `X-Correlation-Id` qua filter HTTP, ghi vào SLF4J MDC và truyền qua mọi tầng log.
   - Khi có lỗi, chỉ cần tìm theo `correlationId` là có toàn bộ trace log của request mà không bị lẫn lộn giữa các thread.
3. **Metrics & Dashboard:**
   - Spring Boot Actuator kết hợp Prometheus/CloudWatch đo lường: RPS, Latency percentiles (p50/p95/p99), HikariCP pool active/idle connections, JVM heap memory, và tỉ lệ giao dịch bị cờ nghi vấn (Risk flags count).

---

## 11. How much does it cost? (Ước tính chi phí & FinOps)

Hệ thống được thiết kế theo tư duy FinOps tối ưu chi phí rõ ràng với 2 kịch bản:

### Phương án 1: Managed Cloud Planning Estimate (AWS Singapore)
* **Backend Compute:** AWS ECS Fargate (0.5 vCPU, 1 GB RAM) = ~$15.20/tháng.
* **Database:** AWS RDS PostgreSQL `db.t4g.micro` (Single-AZ, 20 GB gp3) = ~$16.50/tháng.
* **Frontend:** AWS S3 + CloudFront CDN edge = ~$1.80/tháng.
* **Ingress & Networking:** Application Load Balancer (ALB) = ~$10.00/tháng.
* **Logging & Monitoring:** AWS CloudWatch (5 GB Logs) = ~$3.50/tháng.
* **Tổng listed-resource estimate:** **~$45-$50/tháng trước chi phí loại trừ**. Đây là planning estimate, không phải price quote. Verify provider calculator, region, account eligibility, data transfer, backup, registry, IPv4, NAT, domain và monitoring overage trước provisioning.

### Phương án 2: Potential Free-Tier / Hobby
* Có thể đạt **$0 trong quota miễn phí đủ điều kiện**, nhưng không bảo đảm. Quota, sleep/auto-suspend, region và account eligibility phụ thuộc provider. Không dùng auto-suspend database cho load test liên tục 20 RPS.

---

## 12. If AI / Data feature is used: quality, safety and cost? (Tính năng AI / Dữ liệu)

* **MVP:** Rule-based detection runs after transfer commit. Flags are read-only in MVP; review endpoint and notes are post-MVP options. Rules do not block transfers.
  - Rule 1: Giao dịch vượt ngưỡng cấu hình lớn (`amount > 5,000,000 VNĐ`).
  - Rule 2: Tần suất giao dịch bất thường trong thời gian ngắn (`> 5 transactions / 10 phút`).
* **Đảm bảo chất lượng & An toàn (Quality & Safety):**
  - Đánh giá cờ sau khi giao dịch chuyển tiền đã commit thành công (`Post-commit Evaluation`). Cờ nghi vấn **không bao giờ chặn hoặc rollback tiền của người dùng** trong MVP, tránh gây gián đoạn thanh toán ngoài ý muốn (False Positive impact).
  - MVP flags read-only; Operator/Auditor xem danh sách. Review workflow là post-MVP option.
* **Chi phí & Lộ trình nâng cấp ML (Future ML Roadmap):**
  - **Chi phí MVP:** Không thêm hạ tầng riêng cho risk rules; đo overhead sau implementation. Không khẳng định `<1 ms` trước benchmark.
  - **Future options:** ML scoring and event streaming require separate dataset, privacy, quality and operational design; none is part of MVP.
