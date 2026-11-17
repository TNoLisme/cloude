# BE-TRF-07 — Evidence kiểm thử candidate

Ngày 17/11/2026. Baseline HEAD `9d96c96` trên `dev2`, cùng thay đổi working tree BE-TRF-06 đã có. Chạy candidate trong bản sao tạm, Java Temurin 21, Gradle 8.14.3 offline, PostgreSQL 16 Alpine qua Testcontainers. Fixtures dùng ID, account và dữ liệu synthetic.

## Lệnh tái lập

Từ `digitalbanking-backend`

```sh
./gradlew test \
  --tests 'com.simulatedbank.banking.transfer.*' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurityComponentsTest' \
  --tests 'com.simulatedbank.banking.common.context.RequestContextTest' \
  --offline --console=plain
```

Từ thư mục [ops](../ops/README.md)

```sh
promtool check rules BE-TRF-07.rules.yml
promtool test rules BE-TRF-07.rules.test.yml
```

**Kết quả:** BUILD SUCCESSFUL. 243 test đạt, gồm 206 Transfer và 37 Common. Trong bộ Transfer có 53 test PostgreSQL. XML ghi 0 failure, 0 error và 0 skipped. `promtool check rules` và `promtool test rules` đều đạt.

## Phạm vi xác minh

| Vector | Bằng chứng |
|---|---|
| TRF07-CTX-01/02 | Regression HTTP replay/headers, T1 correlation binding và PostgreSQL recovery. Hai bản ghi xử lý cùng thread có correlation bền riêng, request ID mới, không giữ IP/User-Agent; lỗi ở bản ghi đầu vẫn khôi phục context trước đó. |
| TRF07-SEC-01 / PII-01 | Fixture HTTP dùng Common filter với session mock. Test query từ chối staff ngoài scope trước đọc fraud flags. Canary C3 có OTP giả, CR/LF và actor thô; JSON giữ action/outcome/actor đã hash và context hợp lệ. Còn chờ quét PII xuyên tất cả artifact và provider thật. |
| TRF07-AUD-01 | C3 empty/exception cùng fallback, metric lỗi vẫn giữ quyết định. C1 failure trong fixture PostgreSQL rollback terminal/money/ledger/idempotency/reservation/outbox. Orchestrator map audit write failure thành mã an toàn. Audit chain provider thật và lỗi từng C2 còn chờ tích hợp. |
| TRF07-MET-01 | Terminal metric sau commit ngoài, rollback không tăng; lỗi audit vẫn có signal; timer COMMITTED/ROLLED_BACK, registry scrape khớp tên rule và có histogram. Pending snapshot dùng primary, gồm MANUAL và phân biệt unknown/stale/empty. |
| TRF07-OUT-01 / CON-01 | Policy synthetic own-account dedupe, route ngoài transfer và template mâu thuẫn bị từ chối, transaction bắt buộc. Fixture PostgreSQL rollback khi enqueue hoặc audit lỗi. Guard ArchUnit giữ boundary. T3 thật, uniqueness migration và policy consent/recipient còn Blocked. |
| TRF07-OPS-01 | Năm rule candidate qua kiểm tra cú pháp và test fire/resolve. Chờ runbook drill, định tuyến Ops, metric lock/deadlock và backlog thật. |

## Kiểm tra mở rộng và giới hạn

Lần chạy mở rộng với `com.simulatedbank.banking.notification.*`, `com.simulatedbank.banking.common.context.*` và `com.simulatedbank.banking.common.config.HttpSecurity*` ghi nhận 31 lỗi ở test setup. Chạy lại năm lớp bị ảnh hưởng trên bản sao HEAD tái hiện 31 lỗi. Common có 13 lỗi khởi tạo context thiếu `JdbcTemplate`; Notification có 18 lỗi khởi tạo do Flyway trùng migration version 2. Các lỗi này chặn bằng chứng cross-module thật.

**Nghiệm thu/activation còn Blocked.** Các fixture về tiền và outbox dùng provider synthetic cùng transaction; chúng không xác minh API/migration Notification thật, T3 contact resolution/delivery/dedupe, shared quota, scope provider, Audit chain thật hoặc lịch sử correlation đầy đủ ở Identity/Ledger. Evidence provider và review Transfer/Notification/Common/Identity/Customer/Audit/Security/QA/Ops vẫn cần theo [BE-TRF-07](../BE-TRF-07.md).
