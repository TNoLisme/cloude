# BE-TRF-06 — Evidence kiểm thử candidate

Ngày chạy 17/11/2026, branch tại HEAD `9d96c96` và thay đổi working tree chưa commit. Runner Codex dùng Temurin 21, Gradle 8.14.3 và PostgreSQL 16 Alpine qua Testcontainers. Kiểm thử trong bản sao tạm để cô lập build, với source/resources trùng workspace và OpenAPI từ repo.

Từ `digitalbanking-backend`:

```sh
./gradlew test --tests 'com.simulatedbank.banking.transfer.*' --offline --console=plain
```

**BUILD SUCCESSFUL.** Tổng cộng **195 test Transfer** trong 21 suite. Có **50 test PostgreSQL** trong bốn suite, gồm **24 test recovery mới**. XML báo 0 failure, 0 error và 0 skip.

| Nhóm | Bằng chứng trong candidate |
|---|---|
| T-REC-01 / T-IDEM-05 | Claim đã commit quan sát được từ connection độc lập trước T2. Crash trước claim commit để lại IDLE và count 0. Restart sau terminal không chạy lại T2. Claim hết lease sau khi worker rời đi chuyển MANUAL. |
| T-REC-02 | Evidence đủ cho repair với reservation PENDING, SETTLED và legacy usage có proof. Kiểm response 201, TTL tối thiểu, expiry không rút ngắn, ngày nghiệp vụ gốc, audit và settlement. T2 không được gọi trong nhánh repair. |
| T-REC-03 / T-AUD-06 | Evidence thiếu, trùng, RELEASED, mode/response thiếu hoặc response sai status và lỗi read đi MANUAL. C1 lỗi rollback cả transfer, idempotency và settlement fixture. Fault trước/sau commit kiểm quyết định qua primary và giữ terminal đã commit. |
| T-REC-04 | Guard/row lock bận không tăng attempt. Worker thứ hai cạnh tranh khi lease hết trong lúc worker đầu còn giữ lock. Stale epoch/token bị từ chối. Cursor đi qua batch 100 row bận. |
| Retry và quyền | Cùng backoff bền qua restart, first/next/count, 5 attempt hoặc 15 phút, system authorization lỗi chặn recovery/purge. |
| TTL/purge | Test model biên expiry nghiêm ngặt. PostgreSQL giữ long-PENDING kể cả khi idempotency mang status terminal, bỏ qua idempotency đang khóa, xóa record 201/422 hết hạn và cho phép reuse key sau xóa. Transfer còn nguyên. |
| Regression | Bộ Transfer gồm HTTP/OpenAPI với common error/security thật và mock module khác, cùng JPA migration, domain, T1/T2 và ArchUnit. |

Fixture PostgreSQL dùng migration khởi tạo schema V1, provider key giả và migration Transfer candidate. Nó tránh nạp migration provider production trùng V2. Fixture HTTP đăng ký tường minh Transfer và common web layer để tránh khởi động Audit persistence. Các seam Account/Ledger/Limit/Audit và T2 trong test recovery là mock. Các test PostgreSQL T2 riêng kiểm tra money/ledger/rollback với provider synthetic. Hai fault commit chèn lỗi ở transaction manager quanh commit PostgreSQL. Kiểm thử mất ACK thật qua network/failover còn chờ môi trường QA.

**Nghiệm thu/activation còn Blocked.** Cần provider thật chia sẻ guard/transaction, nguồn bền cho mode/version và replay, migration production, proof fencing với direct T2/Tfail, rearm phân quyền và audit, diễn tập runbook và review owner/Ops/QA. Xem [BE-TRF-06](../BE-TRF-06.md).
