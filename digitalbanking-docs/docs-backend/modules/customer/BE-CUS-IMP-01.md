# BE-CUS-IMP-01 — Flyway migration `banking.customer`

> **Ngày:** 16/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** DONE — migration, fresh PostgreSQL constraints và full backend regression đã PASS.
> **Quyết định version:** [DEC-CUS-001](../../../../DECISIONS.md).

## A. Phạm vi triển khai

- Thêm `digitalbanking-backend/src/main/resources/db/migration/V5__create_customer_table.sql`.
- V5 là version trống đầu tiên sau V1–V4 hiện hữu; chạy sau V2 tạo `banking.app_user`. Không sửa migration đã áp dụng, không bật out-of-order.
- Tạo duy nhất `banking.customer` theo `docs-common/04. Database design & analysis.md` §4.2.2:
  - `customer_id BIGINT GENERATED ALWAYS AS IDENTITY` làm PK.
  - `user_id BIGINT NOT NULL`, unique và FK tới `banking.app_user(user_id)`.
  - `full_name_enc BYTEA NOT NULL`; `phone_enc`, `email_enc BYTEA NULL`.
  - `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`, chỉ `ACTIVE`/`INACTIVE`.
  - `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - FK `ON DELETE RESTRICT ON UPDATE RESTRICT`; không cascade/hard delete.
  - Unique constraint/index `uq_customer_user_id`; index `ix_customer_status`.
- Không thêm Account, CIF, KYC, national ID, blind index, API/service/entity hoặc status ngoài thiết kế.

## B. Kiểm thử PostgreSQL

Thêm `CustomerV5MigrationPostgreSqlIntegrationTest`, chạy với Testcontainers `postgres:16-alpine` và Flyway từ database trống. Chín scenario:

1. V5 có bản ghi thành công trong `banking.flyway_schema_history`; bảng có đúng tám cột, type/null/default/identity đã duyệt.
2. PK đúng `customer_id`; FK đúng schema/table/column và action RESTRICT; đúng ba index PK/unique/status; CHECK status đúng.
3. Insert customer hợp lệ, nullable phone/email, default status/timestamp và join Identity hợp lệ.
4. Duplicate primary key bị từ chối.
5. Duplicate `user_id` bị từ chối.
6. Identity reference không tồn tại bị từ chối.
7. Thiếu `full_name_enc` bị từ chối.
8. Status ngoài `ACTIVE`/`INACTIVE` bị từ chối.
9. Delete/update key của `app_user` đang được Customer tham chiếu bị từ chối.

Lệnh đã chạy tại `digitalbanking-backend`:

```bash
./gradlew test --tests 'com.simulatedbank.banking.migration.CustomerV5MigrationPostgreSqlIntegrationTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.migration.*' --console=plain
./gradlew build --rerun-tasks --console=plain
```

Kết quả source hiện tại:

- Focused Customer: **BUILD SUCCESSFUL**, 9/9 PASS, 19 giây.
- Migration regression: **BUILD SUCCESSFUL**, toàn bộ package migration PASS, 18 giây.
- Full backend: **BUILD SUCCESSFUL**, 9/9 Gradle tasks executed, 56 test classes / 803 tests / 0 failure / 0 error / 0 skipped, 6 phút 59 giây.
- Lần focused đầu phát hiện assertion kỳ vọng sai exception khi update cột identity trực tiếp: PostgreSQL chặn câu lệnh bằng SQLSTATE của generated identity trước khi đánh giá FK. Test được sửa dùng `SET user_id = DEFAULT`, nhờ đó thực sự đi tới FK `ON UPDATE RESTRICT`; chạy lại PASS. Migration SQL không cần sửa.

## C. Dependency và ownership

- Identity migration **có sẵn và tương thích**: V2 tạo `banking.app_user(user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY)` trước V5.
- Schema `banking` có từ V1. Spring Flyway dùng `classpath:db/migration`, `schemas/default-schema=banking`, `validate-on-migrate=true`, `out-of-order=false`; Hibernate `ddl-auto=validate`.
- Local Compose tạo `banking` do `banking_migrator` sở hữu và cấp default table/sequence privileges cho `banking_app`; V5 không hardcode credential hoặc grant production mới.
- Chênh lệch version giữa bảng gợi ý cũ ở backend architecture §8.5 và V3/V4 Identity thực tế được ghi tại DEC-CUS-001. Không có unresolved schema/business decision trong phạm vi §4.2.2.

## D. Acceptance

| Tiêu chí | Trạng thái | Evidence |
|---|---|---|
| Migration unique, sau Identity | PASS | V5 sau V2–V4; Flyway fresh migration PASS |
| Bảng/cột/type/null/default | PASS | PostgreSQL catalog assertions |
| PK/FK và RESTRICT | PASS | Catalog + insert/delete/update constraint scenarios |
| Unique `user_id` | PASS | Duplicate rejected |
| Status/default/CHECK | PASS | Default ACTIVE; invalid status rejected |
| Index bắt buộc | PASS | `uq_customer_user_id`, `ix_customer_status` catalog assertions |
| Fresh PostgreSQL migration | PASS | Testcontainers PostgreSQL 16 |
| Constraint tests | PASS | 9/9 focused scenarios |
| Full backend regression | PASS | 56 classes / 803 tests / 0 failure/error/skip |
| Documentation/checklist | PASS | Báo cáo và checklist BE-CUS-IMP-01 đã cập nhật |

## Change Log

| Ngày | Mô tả |
|---|---|
| 16/11/2026 | Triển khai V5, PostgreSQL migration/constraint tests và ghi nhận conflict version forward-only. |
