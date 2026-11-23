# BE-INT-05 — Kiểm chứng Transfer T0/T1

| Mục | Kết quả |
|---|---|
| Ngày | 19/11/2026, Asia/Ho_Chi_Minh |
| Môi trường | Java 21, Gradle 8.14.3, PostgreSQL 16 Testcontainers, profile `test`. Test T0/T1 dùng role `banking_app` và role fixture/migrator riêng. |
| Hồi quy | 487/487 qua 56 lớp trước các chỉnh sửa cuối. Lần chạy cuối đạt **314/314 qua 27 lớp**, gồm **17/17** trong `TransferT0T1PostgreSqlIntegrationTest`. Hai lệnh đều exit 0, fail/error/skip đều 0. |
| Schema | Flyway validate chuỗi canonical V1–V20. V20 checksum `1968807172`. |
| Revision | Working tree trên `dev2@ba3441cc035ed34c5153e28c10b7e86cb7011971`. [Manifest source lần cuối](evidence/BE-INT-05/source-manifest.sha256). |
| Bằng chứng | [JUnit XML gốc, số ca và log Gradle](evidence/BE-INT-05/results.tar.gz) · [SHA-256 của gói kết quả](evidence/BE-INT-05/results.sha256). |

## Kết quả nghiệm thu

| Vector | Kết quả và cách xác minh |
|---|---|
| INT05-01 · HTTP, preflight, replay | MockMvc chạy security chain và Identity session thật. Kiểm tra 401, cookie/CSRF và ownership 403, thiếu key 400, resolve số có số 0 đầu, denial Customer INACTIVE và C3 theo scope. Hai terminal fixture 201/422 vẫn replay status/body khi account đích khóa và Customer inactive, kể cả record quá TTL. Khoá bảng Account từ kết nối khác để tạo key trong lúc preflight đang chờ, rồi xác nhận lần đọc lại trả 409. |
| INT05-02 · Guard và delivery | Hai request từ hai account chung Customer tranh guard, tổng đúng 20 triệu không yêu cầu proof, 20 triệu + 0,01 yêu cầu OTP, đúng 10 triệu/lệnh kích hoạt OTP. Reservation ngày trước vẫn được tính. OTP fixture thực thi sau commit và ngoài transaction. Reject, timeout và ACK mất CAS trả 503. Thử request cùng key commit khi gateway còn chờ, sau đó retry trả 409 và handoff vẫn chỉ một lần. |
| INT05-03 · Binding | Verify qua endpoint Identity, gồm OTP sai và không đúng user. Kiểm hash/key/customer mismatch, lookup vắng, duplicate và lỗi DB, reissue sau expiry, rollback khi proof hết hạn sau lookup. Hồi quy Identity kiểm purpose, single-use đồng thời và expiry sau khi chờ row lock. UUID trên API trỏ đúng ID BIGINT attach trong T1. |
| INT05-04 · Atomic T1 | Race cùng Customer và race cross-customer chờ hai INSERT thật qua `pg_blocking_pids`. Chỉ request thắng có một transfer, key, reservation và C1. Lỗi reserve, lỗi attach FK, lỗi C1 và lỗi C2 ở create/verify đều được chèn ở PostgreSQL và quan sát từ kết nối độc lập. Rollback T1 khôi phục consume. Kiểm số dư, ledger, usage và notification kết quả. |
| INT05-05 · TTL và handoff | Snapshot 24h và 48h lưu đúng version và expiry, 23h dừng trước khi ghi intent. Fixture T2 đọc được cả bốn bản ghi trên kết nối độc lập sau commit, sau đó trả 503. Retry nhận 409 và không chạy lại handoff. Unit test đưa lỗi commit không rõ vào orchestrator và xác nhận primary re-read cùng quy tắc không handoff. |

## Lệnh tái lập

Chạy trong `digitalbanking-backend`, trên máy có Docker Desktop và JDK 21. Bộ lọc theo DEC-INT-007 bao phủ Transfer T0/T1, HTTP contract, Limit provider, rollback Audit, Identity lifecycle và Admin consumer phụ thuộc interface.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export DOCKER_HOST="$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# Hồi quy trước các tinh chỉnh cuối về TTL và audit projection
./gradlew test --tests '*Transfer*' --tests '*OtpChallenge*' --tests '*OtpDelivery*' \
  --tests '*StepUp*' --tests '*SecurityLogAuditAdapterTest' --tests '*LimitAdminHttpIT' \
  --tests '*AuditModulePostgreSqlIntegrationTest' --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest' \
  --tests '*AuditBoundaryArchTest' --tests '*ModuleBoundaryArchTest'

# Xác minh trên source cuối cùng
./gradlew test --tests '*TransferT0T1PostgreSqlIntegrationTest' \
  --tests '*banking.transfer.application.*' --tests '*banking.transfer.adapter.inbound.*' \
  --tests '*TransferBoundaryTest' --tests '*TransferLimitPostgreSqlIntegrationTest' \
  --tests '*AuditEventValidatorTest' --tests '*AuditDetailProjectionTest' \
  --tests '*SecurityLogAuditAdapterTest' --tests '*LimitAdminHttpIT'
```

## Phạm vi bằng chứng

Spring context dùng Identity, Customer, Account, Limit, Audit và Transfer persistence thật. OTP transport, handoff T2 và các port chỉ phục vụ GET Transfer là fixture. Các phản hồi terminal 201/422 đến từ bản ghi fixture để kiểm replay. Lỗi commit không rõ được tiêm tại seam orchestration, còn mất response sau commit được kiểm với T1 và PostgreSQL thật. Production activation phụ thuộc Account/Ledger/Limit T2, Fraud/Audit T2, OTP delivery thực và recovery/fence tại BE-INT-06/07/08/10. T0 đã kiểm chứng policy theo ngưỡng tiền. FR-026 còn cần contract và kiểm thử cho tín hiệu thiết bị/hành vi nếu dùng để quyết định step-up. Báo cáo này xác nhận phạm vi T0/T1 và handoff, còn các cổng tích hợp toàn dự án được đánh giá ở task tương ứng.
