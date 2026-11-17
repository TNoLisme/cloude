# Kiểm chứng refactor Limit và Admin LIMIT_CHANGE

## Phạm vi và căn cứ

Phần REF-01–05 trong `DELIVERY-PLAN.md` áp dụng cho Limit E1–E4 và approval LIMIT_CHANGE thuộc Admin. Nguồn yêu cầu: `docs-common/03` (FR-016, FR-031–033, AC-009–013/017/019/028/034), `04` §4.5/§7.7, `05` §5.2, `06` §6–8, `07` §8.5, `09` và `design/05` §9–§13. `DEC-LIM-001` áp dụng cho GET không chọn account. Các contract liên module không đổi; chữ ký candidate và provider thật vẫn cần owner review.

## Baseline

- HEAD: `5bacbeea6318605917b6c28c5354d489457ea3ad`; workspace có thay đổi E3, common, API/docs và các thay đổi khác có sẵn trước phiên, nên HEAD một mình không đại diện baseline.
- Lệnh: `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2`.
- Kết quả: 218 tests, 0 failures, 0 errors, 0 skipped; PostgreSQL 16 qua Testcontainers. JUnit XML được lưu tại `report/limit-refactor/baseline-junit.tar.gz`. Chỉ dữ liệu tổng hợp.

## Ma trận operation, nguồn và kiểm chứng

| Operation và caller | Nguồn / behavior giữ lại | Test PostgreSQL / contract |
|---|---|---|
| Limit config, usage insert/read; `LimitRules` | 04 §4.5; ACTIVE, thời gian, hai scope; tiền chính xác, ID và timestamp DB | `LimitPersistenceIT`, `LimitRulesIT` |
| Effective snapshot/usage GET; `LimitCustomerService` | 07 §8.5.1; DEC-LIM-001, ownership, một câu SQL, không khóa hay tạo usage | `LimitCustomerHttpIT`, `LimitCustomerMissingProviderIT` |
| Transaction start, scope advisory lock, usage upsert/row lock, conditional increment; `LimitEnforcement` | 04 §7.7; design/05 §10; READ COMMITTED, ngày cố định, không vượt limit, rollback và handle dùng một lần | `LimitEnforcementIT`, `LimitBusinessDateIT` |
| Proposal/approval, config lock/version/supersede/reject; `LimitChanges` | 04 §4.5; 07 §8.5.2–3; maker/checker, policy, stale, guard account và không reset usage | `LimitChangesIT`, `LimitAdminHttpIT` |
| Admin approval create/read/lock/update; `LimitApprovalService` | 04 §4.5.3; 07 §8.5.2–3; JSONB, step-up và audit trong transaction | `LimitAdminHttpIT`, `LimitAdminMissingProviderIT`, `LimitApprovalServiceTest` |
| Boundary và mapper | 05 §5.2; 06 §6; design/05; API/projection không lộ entity, application gọi port | `ModuleBoundaryArchTest`, các test HTTP và round-trip |

Spring Data JPA dùng native DML có kiểm tra số dòng cho PostgreSQL upsert/update; các snapshot JPA được detach sau khi đọc để native DML không bị entity cũ ghi đè. Không đổi migration, grant, API hoặc bảng thuộc module khác. JPA dùng identity insert và refresh cho giá trị do DB sinh. `SqlFixture` chỉ có trong test, dùng SQL độc lập để thiết lập/quan sát DB và giữ transaction của test; không là persistence production.

## Kết quả sau refactor

- Lệnh: `./gradlew check --offline --console=plain --max-workers=2` — **BUILD SUCCESSFUL**, 425 tests, 0 failures/errors/skipped. Chạy ngày 17/11/2026, Java 21.0.13, Docker 29.8.0, PostgreSQL 16-alpine qua Testcontainers. XML của 38 test suite: `report/limit-refactor/final-junit.tar.gz`.
- `report/limit-refactor/source.sha256` ghi hash 140 file source/resources/Gradle/OpenAPI trên workspace dirty đã kiểm thử; HEAD gốc nêu ở baseline. Nó định danh source, không phải một snapshot đầy đủ có thể tự dựng lại.
- Các ca bổ sung xác nhận entity không biến currency/status `null` thành DB default, proposal với timestamp DB sinh và `ON CONFLICT`, và các rule ArchUnit từ chối fixture vi phạm. Test concurrency/rollback, GET, Admin HTTP và role DB được chạy lại trên PostgreSQL.
- `git diff --check` trên tracked working-tree đạt. Khi stage cả tài liệu E3 có sẵn, `git diff --cached --check` báo bốn dòng Markdown kết thúc bằng hai dấu cách có chủ ý để xuống dòng; giữ nguyên nội dung đó. Tìm `JdbcTemplate`, `NamedParameterJdbcTemplate`, `ResultSet` trong production `limit`/`admin` không có kết quả. SQL fixture test dùng `JdbcTemplate` nội bộ để giữ oracle độc lập và tham gia transaction test.
- Parse YAML OpenAPI thành công và kiểm tra danh sách response của GET `/limits`, POST `/admin/limits`, POST `/admin/limits/{id}/approve`; đây không phải OpenAPI lint đầy đủ.

## Giới hạn

Các test dùng PostgreSQL thật và `banking_app` cho các luồng chính; Identity, Account, Customer, Staff và Audit trong test tích hợp là test double/SQL fixture, không chứng minh provider production. LIM-05/Transfer thật, kết nối provider thật, C3 alert và timezone D2 vẫn chưa nghiệm thu. Baseline không có snapshot đầy đủ của workspace dirty nên không thể tái dựng độc lập chỉ từ HEAD và XML. Không coi kết quả refactor là owner sign-off hoặc release money path.
