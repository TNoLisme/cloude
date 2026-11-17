# BE-TRF-05 — Evidence bổ sung cho candidate

Ngày 17/11/2026, Codex. Phạm vi là candidate không đăng ký production, không phải nghiệm thu BE-TRF-05.

- `Flag.ruleVersion` dùng `int` dương như contract v1; test xác nhận version 1 và từ chối 0/âm. Kiểu Java không nhận chuỗi; kiểm tra adapter provider, overflow và round-trip vẫn cần khi tích hợp.
- Fraud fail-open chỉ áp dụng `FraudEngineException` sau khi provider opt-in, rollback savepoint thành công, xác minh persistence context usable và transaction không rollback-only. Fail-open yêu cầu telemetry; lỗi telemetry, lỗi savepoint, lỗi invariant hoặc không chứng minh được context làm rollback toàn T2. Mặc định không opt-in và lỗi fraud propagate. Các flag đã rollback không được audit.
- Kiểm thử mock xác minh thứ tự, điều kiện từ chối và rollback; PostgreSQL Testcontainers dùng bảng/provider synthetic kiểm tra partial fraud write được rollback trước khi money path tiếp tục. Kết quả fixture không chứng minh transaction manager JPA, persistence context hoặc provider thật.
- Credit savepoint/Tfail, bounded retry, timeout và durable fencing/recovery chưa được triển khai/chứng minh. Không kích hoạt runtime hoặc đánh dấu AC/release Verified; provider contract, migration, concurrency, security và owner review vẫn cần hoàn thành.

**Xác minh:** Java 21, Gradle 8.14.3 offline, PostgreSQL 16-alpine qua Testcontainers; cache Gradle sao chép vào `/private/tmp/cloude-gradle` do sandbox. Chạy `GRADLE_USER_HOME=/private/tmp/cloude-gradle JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home <gradle-8.14.3>/bin/gradle check --offline --console=plain` tại `digitalbanking-backend`: BUILD SUCCESSFUL, 374 test, 0 failure/error/skipped. Trong đó settlement có 26 test mock và 6 PostgreSQL fixture. `git diff --check` thành công. Source là working tree chưa commit; chưa có reviewer và không có dữ liệu khách hàng thật.
