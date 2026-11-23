# BE-CUS-IMP-02 — Customer JPA entity, repository và PostgreSQL mapping

> **Ngày:** 16/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** DONE — focused PostgreSQL tests, migration compatibility và full backend build đều PASS.
> **Schema dependency:** [BE-CUS-IMP-01](BE-CUS-IMP-01.md), [DEC-CUS-001](../../../../DECISIONS.md).

## A. Phạm vi triển khai

- Thêm `customer.domain.CustomerStatus` đúng hai trạng thái đã duyệt: `ACTIVE`, `INACTIVE`.
- Thêm `customer.adapter.out.CustomerEntity` ánh xạ nội bộ tới `banking.customer`:
  - `customer_id` dùng `GenerationType.IDENTITY`/`Long`.
  - `user_id` là `Long`, `NOT NULL`, unique, immutable sau insert; chỉ giữ identifier, không tạo JPA relationship tới Identity.
  - `full_name_enc`, `phone_enc`, `email_enc` dùng `byte[]`/PostgreSQL `BYTEA`; name bắt buộc, phone/email nullable.
  - `status` dùng `EnumType.STRING`, độ dài 16.
  - `created_at`, `updated_at` dùng `Instant`; Hibernate `@CreationTimestamp`/`@UpdateTimestamp` quản lý lifecycle, tương thích `TIMESTAMPTZ` và default DB.
- Entity không có credential/authentication secret, không dùng Lombok `@Data`, không có `equals/hashCode` theo ciphertext và `toString()` luôn redacted. Constructor/accessor persistence giữ package scope; entity không xuất hiện trong `customer.api` hoặc REST DTO.
- Thêm `CustomerJpaRepository` nội bộ, kế thừa `JpaRepository<CustomerEntity, Long>`.
- Không sửa V5 hoặc thêm migration/schema ngoài tài liệu đã duyệt.

## B. Repository operations

| Operation | Semantics |
|---|---|
| `findById` | Lookup theo `customer_id`, trả `Optional` qua Spring Data |
| `findByUserId` | Lookup mapping duy nhất `user_id -> customer`, trả `Optional`; giữ nguyên status kể cả `INACTIVE` |
| `existsByUserId` | Pre-check uniqueness; không thay thế unique constraint DB |
| `existsByCustomerIdAndStatus` | Kiểm tra status explicit mà không load PII ciphertext |
| `saveAndFlush` | Flush để constraint PK/FK/NOT NULL/CHECK/unique của PostgreSQL là consistency boundary cuối |

Repository không filter ngầm `INACTIVE`, vì như vậy sẽ nhập nhằng customer thiếu với customer inactive. Service/CustomerQuery sau này phải đọc status và áp dụng eligibility theo contract được duyệt; IMP-02 không tự tạo transition hoặc tự activate. Persistence exception chưa map sang business/API error tại repository; translation thuộc application boundary có write use case được duyệt, hiện chưa có Customer create API.

## C. Database mapping và constraint

| Flyway V5 | JPA | Kết quả |
|---|---|---|
| `BIGINT GENERATED ALWAYS AS IDENTITY` | `Long`, `GenerationType.IDENTITY` | Khớp |
| `user_id BIGINT NOT NULL UNIQUE` | `Long`, `nullable=false`, `unique=true`, `updatable=false` | Khớp; DB vẫn là final boundary |
| `*_enc BYTEA` | `byte[]` | Khớp PostgreSQL; không plaintext |
| `status VARCHAR(16)` + CHECK | `CustomerStatus`, `EnumType.STRING`, length 16 | Khớp `ACTIVE/INACTIVE` |
| `TIMESTAMPTZ NOT NULL` | `Instant`, creation/update timestamps | Khớp UTC mapping |
| FK `user_id -> banking.app_user` RESTRICT | scalar Identity identifier | Không đọc bảng/entity Identity qua relationship |

`spring.jpa.hibernate.ddl-auto=validate` khởi tạo thành công trên fresh PostgreSQL 16 sau Flyway V1–V5. Unique `uq_customer_user_id` chặn cả insert tuần tự và hai insert đồng thời; failed insert không để lại row thứ hai.

## D. Kiểm thử

Thêm `CustomerJpaPostgreSqlIntegrationTest` dùng Testcontainers `postgres:16-alpine`, Flyway thật và Spring Data JPA thật. Sáu scenario:

1. Persist/reload toàn bộ field, IDENTITY, enum, ciphertext, timestamps và lookup ID/user.
2. Phone/email nullable; `full_name_enc` null bị từ chối sau flush.
3. Missing customer theo ID/user trả `Optional.empty()` và existence false.
4. `INACTIVE` persist/reload nguyên trạng, không bị coi là missing/ACTIVE.
5. Duplicate `user_id` tuần tự thất bại tại flush và transaction thứ hai rollback; row đầu còn nguyên.
6. Hai insert đồng thời cùng `user_id`: đúng một transaction thành công, một transaction bị unique constraint từ chối.

Lệnh đã chạy tại `digitalbanking-backend`:

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerJpaPostgreSqlIntegrationTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.migration.CustomerV5MigrationPostgreSqlIntegrationTest' --console=plain
./gradlew build --console=plain
```

Kết quả:

- Customer JPA focused: **BUILD SUCCESSFUL**, 6/6 PASS, 12 giây.
- Customer V5 migration: **BUILD SUCCESSFUL**, 9/9 PASS, 10 giây.
- Full backend: **BUILD SUCCESSFUL**, 57 test classes / 809 tests / 0 failure / 0 error / 0 skipped, 5 phút 11 giây.
- Lần chạy JPA đầu phát hiện seed Identity dùng `JdbcTemplate` ngoài transaction trong khi Hikari `auto-commit=false`, nên seed bị rollback và FK fail. Fixture được sửa dùng `TransactionTemplate`; production mapping không cần sửa. Chạy lại 6/6 PASS.

## E. Dependency và giới hạn integration

- Identity V2 cung cấp `banking.app_user`; Customer chỉ lưu `user_id` scalar và FK V5, không import Identity entity/repository, không truy cập authentication logic.
- Common convention `Instant`, Hibernate UTC, Flyway validate và no-entity-leakage được giữ nguyên; IMP-03 mới tích hợp `PiiCryptoService`/masking.
- Account/Admin/Audit runtime không cần cho persistence tests. CustomerQuery public DTO/service thuộc IMP-04; repository/entity không được expose cho consumer.
- Policy ảnh hưởng của Customer `INACTIVE` tới Account/Transfer vẫn chưa được duyệt; IMP-02 chỉ bảo toàn và truy vấn status, không bịa eligibility/transition.
- Customer provisioning/create API chưa được duyệt; duplicate exception translation/idempotency thuộc write use case tương lai, không thêm ở repository.

## F. Acceptance

| Tiêu chí | Trạng thái | Evidence |
|---|---|---|
| Entity khớp Flyway schema | PASS | Hibernate validate + PostgreSQL persist/reload |
| Repository lookup ID/user/existence/status | PASS | Focused repository scenarios |
| Missing customer | PASS | `Optional.empty()` theo ID và user ID |
| Inactive customer | PASS | Persist/reload `INACTIVE`, không silent activation |
| Duplicate user + rollback | PASS | Sequential và concurrent PostgreSQL constraint tests |
| Migration compatibility | PASS | Fresh V1–V5 PostgreSQL migration test 9/9 |
| Entity không lộ qua API | PASS | Chỉ nằm trong `adapter.out`; repository package-private |
| Full backend build | PASS | `./gradlew build`: 809/809 tests PASS |
| Documentation/checklist | PASS | Báo cáo, module index và task evidence đã cập nhật |

## Change Log

| Ngày | Mô tả |
|---|---|
| 16/11/2026 | Thêm entity/status/repository và 6 PostgreSQL persistence scenarios; focused migration/JPA và full build 809/809 PASS. |
