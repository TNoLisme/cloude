# BE-INT-06 — Kiểm chứng Transfer T2

| Mục | Kết quả |
|---|---|
| Ngày và revision | 19/11/2026, Asia/Ho_Chi_Minh. Working tree trên `dev2@ba3441cc035ed34c5153e28c10b7e86cb7011971`. [Manifest SHA-256](evidence/BE-INT-06/source-manifest.sha256). |
| Môi trường | Java 21, Gradle 8.14.3, PostgreSQL 16 Testcontainers, profile `test`. Spring dùng role runtime `banking_app`. Fixture và kiểm tra kết quả dùng kết nối độc lập. |
| Schema | Flyway validate V1–V20 trong context T2. Dùng V13 cho Transfer/Idempotency, V14 cho Ledger và V20 cho reservation/effect. |
| Kết quả | **268/268 test PASS qua 19 lớp**, gồm **26/26 ca T2**. Gradle exit 0, fail/error/skip đều 0. Bộ lọc theo DEC-INT-007 kiểm đường T0/T1/T2, provider và ranh giới kiến trúc bị ảnh hưởng. |
| Bằng chứng | [JUnit XML, tổng hợp và log Gradle](evidence/BE-INT-06/results.tar.gz) · [SHA-256](evidence/BE-INT-06/results.sha256). |

## Nghiệm thu

| Vector | Kết quả |
|---|---|
| INT06-01 · T1 → T2 → replay | HTTP tạo T1 thật rồi claim có commit riêng. PostgreSQL xác nhận hai loại số dư, hai bút toán với `ledgerBalance` khác `availableBalance`, balance-after và direction đúng, hai scope effect đúng version/amount/count, SETTLED, outbox và C1. Retry trả đúng nội dung đã lưu và correlation gốc trong body, header theo request hiện tại. |
| INT06-02 · Business FAILED, config và ngày | Trạng thái Account và số dư thay đổi sau T1 trả 422. Account thiếu được mô phỏng bằng kết quả có kiểu vì FK canonical giữ hai row. ACCOUNT scope đúng ngưỡng và vượt 0,01 được kiểm. Limit mutation API tiếp nhận cutover sau T1. Tại 00:00 Việt Nam, dùng instant kiểm soát tại seam đồng hồ T2 để kiểm ngày cũ/ngày mới, tài khoản mới áp dụng từ cutover và đồng hồ lùi làm rollback. |
| INT06-03 · Concurrency và fence | Barrier 20 executor trên intent T1 thật cho T-CON-01/02. Cấp đủ thì 20 COMPLETED, cấp đủ cho 10 thì 10 COMPLETED và 10 FAILED vì thiếu số dư. Kiểm A→B/B→A, cặp độc lập và cùng customer khác source tranh daily cap. Kiểm hai executor cùng intent, row bận, epoch/token sai và lease hết hạn. Recovery entry point tham gia transaction caller, kiểm được cả rollback và commit. |
| INT06-04 · Credit và rollback | Trigger trả 0 row ở credit xác nhận debit đã áp dụng trong T2 rồi mới cho phép rollback savepoint và commit `FAILED/PROCESSING_ERROR`. Exception SQL khi credit, Ledger duplicate/read-back lệch, lỗi Limit effect, terminal writer hoặc enqueue đều rollback T2. Kết nối độc lập xác nhận cả số dư, ledger, usage, reservation, key và audit. |
| INT06-05 · Audit, TTL, timeout và commit | C1 lỗi sau business flush trả `AUDIT_WRITE_FAILED` và rollback toàn T2. TTL 23h rollback, TTL 24h giữ expiry T1 cũ dài hơn. Khóa Account thật xác nhận timeout và giới hạn retry. PostgreSQL tiêm SQLSTATE `40P01` ở credit để kiểm retry toàn T2 đúng một lần sau rollback. Spy transaction manager mô phỏng mất ACK sau commit và xác nhận đọc lại cùng kết quả. Ngắt kết nối PostgreSQL trước commit xác nhận thiếu evidence, trả 503 và giữ intent hiện có. |

## Tái lập

Chạy trong `digitalbanking-backend` với JDK 21 và Docker. Các biến dưới đây dùng cho macOS với Docker Desktop.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export DOCKER_HOST="$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

./gradlew test --tests '*TransferT2PostgreSqlIntegrationTest' \
  --tests '*TransferT0T1PostgreSqlIntegrationTest' --tests '*AccountBalance*Test' \
  --tests '*AccountLockPostgreSqlIntegrationTest' --tests '*LedgerPosting*Test' \
  --tests '*TransferLimitPostgreSqlIntegrationTest' --tests '*TransferSettlementCandidate*Test' \
  --tests '*TransferRecoveryCandidatePostgresTest' --tests '*TransferCreateOrchestratorTest' \
  --tests '*TransferApiContractTest' --tests '*TransferBoundaryTest' \
  --tests '*AuditModulePostgreSqlIntegrationTest' --tests '*LimitAdminHttpIT' --console=plain
```

## Phạm vi

`TransferT2PostgreSqlIntegrationTest` import `TransferSettlementIntegrationConfiguration` và `LedgerPostingConfiguration` cùng cấu hình T0/T1 và HTTP. Account, Ledger, Limit, Audit và Notification outbox dùng provider thật. Fraud dùng ALLOW fixture. Policy IN_APP và chuyển đổi DTO outbox do test cấp. GET Transfer và OTP transport là seam trong context này. Kiểm tra cutover dùng Limit mutation API với approval row fixture.

Kích hoạt production và các gate G0/G2/G3 theo kế hoạch tích hợp còn phụ thuộc Fraud/C2 (BE-INT-07), delivery/policy notification (BE-INT-08) và recovery/Tfail (BE-INT-10). Evidence của BE-INT-06 bao phủ đường ghi tiền, terminal replay và rollback dưới các boundary đã nêu. [Kế hoạch tích hợp](README.md)
