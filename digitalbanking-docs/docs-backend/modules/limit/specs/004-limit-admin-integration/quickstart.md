# Kiểm chứng

Java21, Docker/Testcontainers PostgreSQL16 và Gradle cache hiện có. Từ digitalbanking-backend/:

```sh
./gradlew test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.audit.application.AuditWriterIT' --tests 'com.simulatedbank.banking.audit.domain.AuditEventValidatorTest' --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2
./gradlew build -x test --offline --console=plain --max-workers=2
```

Test-only fake session/challenge/staff/customer/account được ghi rõ; approval/config/audit PostgreSQL thật. Không có fake provider production: thiếu identity/session có thể401, thiếu provider sau auth trả503. Cần hai policy max cấu hình rõ theo E4 trước.

Không dùng test HTTP với fake identity để kết luận OTP/session/ownership thật đạt. GET E3/transfer/real identity integration và owner approval của candidate contracts vẫn Blocked trong handoff. Không chạy full regression ngoài scope, không auto commit.
