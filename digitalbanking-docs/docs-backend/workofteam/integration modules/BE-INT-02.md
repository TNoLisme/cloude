# BE-INT-02 — Hợp nhất build, Flyway và đường nâng cấp DB

> **Người thực hiện/chốt:** người điều hành/merge owner theo DEC-INT-003  
> **Ngày:** 18/11/2026  
> **Source:** `phuong/integration@8109504` + working tree BE-INT-02; [source manifest](evidence/BE-INT-02/source-manifest.sha256), PostgreSQL 16 Testcontainers
> **Môi trường đích:** DB canonical mới theo quyết định trực tiếp của người điều hành (DEC-INT-005); chuyển dữ liệu legacy thật **không áp dụng**.
> **Trạng thái:** **DONE / G1 VERIFIED cho DB canonical mới**. Full clean build PASS 2.285 test / 202 class, 0 failure/error/skip; xem [verification report](BE-INT-02-verification.md).

## 1. Phạm vi và chuẩn quyết định

[Kế hoạch tích hợp](README.md) yêu cầu compile, full build, Flyway V1–V19 từ DB trống, Hibernate `validate`, checksum, upgrade/restore cho DB đã áp dụng migration. Nguồn ưu tiên: [Database design §4](../../../docs-common/04.%20Database%20design%20%26%20analysis.md), [Deployment §7](../../../docs-common/08.%20Deployment%20infrastructure.md), [Test plan §10](../../../docs-common/09.%20Test%20plan.md), [Modules contract](../../../docs-common/design/05.%20Modules%20contract.md) và DEC-INT-003/004/005/006. SQL trong `db/migration` là chuỗi runtime; `db/migration-legacy` chỉ là bằng chứng nguồn, không nằm trong Flyway location.

## 2. Lịch sử và checksum

Hai họ lịch sử không tương đương. Version V2–V6 trong `migration-legacy` đã dùng cho Audit/Limit/Admin độc lập; cùng version trong `migration` dùng cho Identity/Customer/Admin canonical. SHA-256 dưới đây là hash **file SQL**, không phải số CRC của Flyway. Flyway lưu và so checksum riêng trong `banking.flyway_schema_history`; test thực hiện `validate()` và `migrate()` với chính hai chuỗi.

| Version | Canonical `db/migration` — SHA-256 | Module-local `db/migration-legacy` — SHA-256 |
|---|---|---|
| V2 | `3ba0c141a10c44279e01f689ac2ffcf34dd49956021bafcc8c8b79d0650c8fe2` Identity/RBAC | `833fd300b9d71116188a1605c960b7e38010ecbf87bd85c7b143e1a0685f1db2` Audit |
| V3 | `f9aea5f7c5e95831be261983ea8d8786bc9cdfef8e879500b78adb099ef481e9` FIDO | `09bec6bdc1287cc240326783297005ef22a723f78a432e99e7122089d25d4dee` Limit tables |
| V4 | `d714b2ef11c005afa2d3bdd195752316b6c174a8ae43ba0d34f821e09a0afe33` role version | `007edf7a86ffd55739ca401d5553d6781e522b7009b27d9e9844fab32a004ec6` Limit grant |
| V5 | `f902b7eb1df6c4f3def1c4247eeaf5ff598cdedc123bbab996608e0d5c79b81e` Customer | `9d54e9f753cb49b17a92aa0cbeea523c9895c3a5b5e6ff1fabc4c64472e44fdf` Limit decision |
| V6 | `04ccec83198af819bb01c51bf4f627378aca1b9a6a2c459fc1a1b8a03089f76b` Admin approval | `bfd9bcca16ea787bc80750570debf449595eb26fdce1b24c1d9579774d70742d` legacy approval |

Để lấy fingerprint của một DB thật, chạy read-only bằng tài khoản có quyền xem metadata:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM banking.flyway_schema_history
ORDER BY installed_rank;
```

Đối chiếu đủ **description, script, checksum, success** và danh mục bảng/cột, vì cùng số version không nói được họ migration. Không dùng `repair` để biến checksum lệch thành lịch sử canonical.

## 3. Kết quả kiểm chứng tại workspace

| Case | Expected | Actual / giới hạn |
|---|---|---|
| `compileJava testClasses` | Source và test compile | PASS trong `./gradlew clean build --offline --no-daemon --max-workers=1`, compile từ đầu. |
| Fresh V1–V19 | DB rỗng chạy đúng 19 migration, rerun 0, constraint/identity/schema có mặt | PASS trong full build; `PostgreSqlFreshMigrationIntegrationTest`. |
| Canonical V7→V19 và V17→V19 | Giữ dữ liệu/sentinel, checksum cũ; quyền UPDATE approval tối thiểu | PASS full build: `AccountV8MigrationPostgreSqlIntegrationTest` + `ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest`; V18/V19 forward-only, V1–V17 không đổi. V19 từ chối NaN; `LimitPersistenceIT` kiểm upgrade V18 có NaN bị chặn mà không sửa dữ liệu/history. |
| Legacy prefix V2, V3, V4, V5, V6 | Flyway canonical từ chối checksum trước business write; history và row count không đổi | PASS trên PG16 bằng `LegacyModuleMigrationPostgreSqlIntegrationTest`. V6 có bản ghi Audit, Limit, Admin giả lập. |
| Backup/restore V6 legacy | `pg_dump -Fc` và `pg_restore` sang DB khác, giữ history và bản ghi | PASS trong cùng test; chỉ chứng minh khả năng phục hồi bản sao giả lập, chưa đo RTO/RPO vận hành. |
| Hibernate `ddl-auto: validate` | Application context boot trên schema V1–V19 | PASS trong full build; các context PostgreSQL validate schema, `PackagedApplicationReadinessIntegrationTest` 4/4 PASS (boot/restart JAR demo với app/migrator riêng và negative startup). |
| Full regression | `./gradlew clean build --offline --no-daemon --max-workers=1` exit 0, 0 failure | PASS, exit 0, **2.285 test / 202 class / 0 failure / 0 error / 0 skip**, BUILD SUCCESSFUL in 13m56s; [artifact](evidence/BE-INT-02/final-results.tar.gz). |

**Artifact:** JUnit XML ở `digitalbanking-backend/build/test-results/test/` và HTML ở `digitalbanking-backend/build/reports/tests/test/index.html`. Test dùng container riêng; không chạm DB môi trường. Các test V6/V7/V8/fresh được đồng bộ kỳ vọng lên V19; không sửa migration đã áp dụng. V18 SHA-256 `6cc88d9863aede2783b89e51f501c548c6f8ac66c7a9cb7e95a49854fc951c75`, V19 SHA-256 `37027a4f88bb186eb5e3de97573ba5fbacdd2b12945e06d65b6942810fa1e0b3`; [manifest checksum](evidence/BE-INT-02/migration-checksums.json) chứng minh V1–V17 giữ nguyên so với HEAD.

## 4. Đường xử lý lịch sử module-local

| Lịch sử quan sát | Hành động |
|---|---|
| DB trống | Chạy Flyway canonical V1–V19 và Hibernate validate. |
| DB canonical prefix V1–V18 | Sao lưu, `flyway validate`, nâng cấp forward-only đến V19, xác nhận history/checksum và invariant dữ liệu. Bài test V7→V19 và V17→V19 là rehearsal hiện có; môi trường thật cần kiểm tra theo revision của nó. |
| DB module-local V2–V6, không có dữ liệu cần giữ | Giữ bản `pg_dump` và SHA-256 để tra cứu; dựng DB canonical mới. Không đổi lịch sử cũ. |
| DB module-local V2–V6, có dữ liệu cần giữ | **Chưa tự động nâng cấp.** Sao lưu và restore vào DB cô lập; kiểm tra row counts/ID, audit chain, quyền và dữ liệu parent; lấy mapping Identity/Customer/Account thật; viết công cụ copy/id mapping theo thứ tự parent → Audit/Approval → Account/Limit/Usage và kiểm FK, số dư, hash, count trước/sau. Diễn tập restore và cutover có khả năng quay về DB nguồn. Không tạo parent giả, không tắt FK, không sửa history/SQL cũ. |

Lý do: legacy V2 không có `app_user`, V3 Limit không có FK Customer/Account/User, V6 Approval không có FK Maker/Checker. Bản ghi nguồn có thể chứa ID chưa hề tồn tại ở schema canonical; copy nguyên xi có thể vi phạm FK hoặc gán nhầm người. Nguồn tham chiếu và chính sách mapping của DB thật là dependency chưa có trong tài liệu; DEC-INT-004 và Modules contract §14 ghi phương án thay thế. Khi có inventory, bổ sung kịch bản copy dữ liệu cụ thể và test trên bản restore của **từng lịch sử DB thực tế** trước khi đánh dấu G1 PASS cho môi trường đó.

## 5. Checklist đóng task

- [x] Compile và test classes của source hiện tại.
- [x] Test fresh V1–V19 và cập nhật expectation V11 cũ.
- [x] Test fail-closed từng prefix legacy V2–V6, không sửa history/dữ liệu khi khởi động sai chuỗi.
- [x] Diễn tập `pg_dump`/`pg_restore` với bản ghi giả lập V6.
- [x] Full regression, Hibernate validate và canonical upgrade trên source hiện tại có report exit 0; source manifest SHA-256 `a9e56ac53112f86c5a78bb3f99e902c96c219d152f7f33a0c3b33b4b40bb66bf`.
- [x] Người điều hành chọn DB canonical mới (DEC-INT-005); inventory/copy dữ liệu legacy thật **N/A** cho môi trường tích hợp này. Quy trình mục 4 vẫn áp dụng nếu sau này chuyển sang DB legacy có dữ liệu cần giữ.

**Đóng BE-INT-02 / G1 VERIFIED cho DB canonical mới** theo DEC-INT-005/006 và evidence trên. Không công bố chuyển dữ liệu legacy thật hoặc G0/G2/G3 PASS; các task tích hợp tiếp theo giữ gate riêng. Báo cáo giữ cả baseline bị ngắt, targeted/full run lỗi và lượt full build cuối để truy vết.
