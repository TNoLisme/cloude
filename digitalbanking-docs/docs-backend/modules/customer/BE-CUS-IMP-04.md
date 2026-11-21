# BE-CUS-IMP-04 — CustomerQuery public API + immutable minimal DTO

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** DONE — focused unit 7/7, PostgreSQL integration 6/6, ArchUnit 9/9 PASS; full backend regression 853/853 PASS (chi tiết phần E, gồm phân tích failure không liên quan ở lần chạy đầu).
> **Phụ thuộc:** [BE-CUS-IMP-02](BE-CUS-IMP-02.md), [BE-CUS-IMP-03](BE-CUS-IMP-03.md), [DEC-CUS-003](../../../../DECISIONS.md).

## A. Phạm vi triển khai

- Thêm hợp đồng public `customer.api.CustomerQuery` (BE-CUS-IMP-04, design/05 §6 đã được owner Customer chốt phần `findByUserId`):
  - `Optional<CustomerIdentity> findByUserId(long userId)` — ánh xạ duy nhất `userId → customerId`.
  - Record bất biến `CustomerQuery.CustomerIdentity(long customerId, long userId, Status status)` — snapshot tối thiểu; chỉ ba field này, không PII.
  - Enum `CustomerQuery.Status { ACTIVE, INACTIVE }` — đúng hai trạng thái đã duyệt của `banking.customer.status` (04 §4.2.2); enum nằm trong `api` để consumer không import `customer.domain`.
- Thêm application implementor `CustomerQueryService` (`@Transactional(readOnly = true)`, condition `app.customer.enabled`): validate userId, tra persistence một lần duy nhất, map sang DTO, không ghi, không decrypt PII.
- Thêm outbound port `CustomerIdentityReader` (`application`, record `Identity(customerId, userId, status)` dùng `domain.CustomerStatus`) và adapter `JpaCustomerIdentityReader` (`adapter.out`, tái sử dụng đúng `CustomerJpaRepository.findByUserId` của IMP-02 — không tạo query thứ hai, không nguồn dữ liệu mới).
- Không thêm migration, REST endpoint, entity import chéo, hoặc quyền mới.

## B. Hợp đồng public API

| Khoản | Nội dung đã duyệt | Bằng chứng |
|---|---|---|
| Signature | `customer.api.CustomerQuery.findByUserId(long userId) → Optional<CustomerIdentity>` | Interface + javadoc nguồn design/05 §6, design.md §5 |
| DTO | `CustomerIdentity(customerId, userId, status)` — record bất biến, không String field (ArchUnit chặn) | `customerApiMustNotCarryTextualPiiFields` |
| Thiếu ánh xạ | `Optional.empty()` — user không tồn tại hoặc user tồn tại nhưng chưa có Customer row; không tự tạo row khi query | Unit + PostgreSQL integration test |
| Status | Row `INACTIVE` vẫn trả snapshot kèm status thực tế; tồn tại ≠ active-eligible; eligibility là việc consumer (CUS-OI-06, chưa duyệt) | Unit + PostgreSQL integration test |
| Input sai | `userId <= 0` → `BankingBusinessException(VALIDATION_ERROR)` trước khi gọi persistence | Unit + PostgreSQL integration test |
| TX | `R` readOnly — không side effect, không đổi `updated_at`, không thêm row | PostgreSQL test "readOnlyLookupNeverWritesOrMutatesPersistedState" |
| `getCustomerView` | **Không thuộc IMP-04** — blocked bởi CUS-OI-02 (mask fullName cho staff chưa có oracle) và CUS-OI-05 (scope/audit runtime); thuộc IMP-06 | tasks/README.md |

Lý do DTO không có `kycStatus` (chênh lệch với draft design/05 §6): `docs-common/03` không có yêu cầu KYC; `docs-common/04` §4.2.2 không có cột KYC/CIF; `docs-backend/modules/_overview/02-customer.md` mô tả CIF/national ID/KYC/FROZEN/CLOSED là nguồn đã ghi nhận xung đột (CUS-OI-01). Theo hierarchy nguồn (01–11 > design/ > overview) và quy tắc "không tự bổ sung", snapshot chỉ giữ `status` ACTIVE/INACTIVE. Chênh lệch đã được ghi lại tại design/05 §6 và [DEC-CUS-003](../../../../DECISIONS.md).

Chi tiết triển khai đáng nhớ: enum api đặt tên `Status` thay vì `CustomerStatus` vì JLS — class implements interface kế thừa nested member types, tên trùng enum domain sẽ bị che khuất trong implementor và ép dùng fully-qualified name.

## C. Tích hợp Identity và Account

| Hướng | Trạng thái | Ghi chú |
|---|---|---|
| Identity → Customer | Sẵn API, wiring còn thuộc Identity | `AuthResponse.customerId` hiện `null` theo BE-ID-IMP-02; giờ có thể resolve qua `customer.api.CustomerQuery.findByUserId(principalUserId)`. Customer không runtime-depend Identity (chỉ scalar `user_id` + FK V5), không import identity entity/repository → không chu trình customer ↔ identity (ArchUnit `modulesMustBeFreeOfCycles` PASS). |
| Account → Customer | Sẵn API, Account runtime chưa tồn tại trên baseline | `account/**` chỉ còn `.gitkeep`. Customer-side contract hoàn chỉnh và được test độc lập; Account không cần Customer entity/repository (ArchUnit `customerInternalsMustNotBeConsumedByOtherModules`). Integration end-to-end thuộc BE-CUS-IMP-07; không tự tuyên bố Account integration hoàn tất. |
| Common | Tái sử dụng | `BankingBusinessException`/`ErrorCode.VALIDATION_ERROR`, condition `app.customer.enabled` — không tự tạo error model mới. |

## D. Kiểm thử và bằng chứng (chạy từ `digitalbanking-backend`)

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.application.CustomerQueryServiceTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerQueryPostgreSqlIntegrationTest' --rerun --console=plain
./gradlew build --max-workers=1 --console=plain
```

- `CustomerQueryServiceTest` — **7/7 PASS** (fake in-memory reader): valid mapping, missing → empty sau đúng 1 lần tra, userId ≤ 0 → VALIDATION_ERROR và không chạm persistence, INACTIVE hiện diện ≠ missing, DTO đúng 3 field + immutable + không String/PII, DTO không mang PII cả trong `toString()`, lỗi persistence lan truyền nguyên vẹn (không nuốt, không reclassify).
- `CustomerQueryPostgreSqlIntegrationTest` — **6/6 PASS** trên PostgreSQL 16 Testcontainers, Flyway V1–V5 thật, JPA thật, inject bean qua interface public `CustomerQuery`: mapping bền vững user→customer, missing (user lạ và user không có row) → empty, INACTIVE trả đúng status, 3 lần đọc không đổi `updated_at`/count (read-only), userId sai → VALIDATION_ERROR, không rò mapping chéo giữa hai customer.
- `ModuleBoundaryArchTest` — **9/9 PASS**: 5 rule cũ (common một chiều, no-cycle slices, domain free, adapter-in→out, common no-persistence) + 4 rule mới cho customer api (api không phụ thuộc entity/persistence/adapter/application/domain; module ngoài không import customer internals; `CustomerQuery` chỉ được implement trong `customer.application`; api không có field String để chặn PII dạng text).

## E. Kết quả full backend regression

- `./gradlew build --max-workers=1 --console=plain` (lần 1): **FAILED** — 12/12 test của `identity.IdentityTransportSecurityIntegrationTest` fail (password login trả 401 thay vì 202). Phân tích: đây là **test mới chưa track của task song song BE-ID-TEST-03** (file untracked, không thuộc baseline 824/824 của IMP-03), không có test nào của Customer trong failure list; app log trong window đó có Hikari pool `PSQLException` (acquire timeout) — khớp pattern flake tài nguyên đã ghi tại DEC-ID-TEST-02/IMP-03 khi hai agent chạy build/Testcontainers/Argon2 (64 MB/hash) đồng thời. Cùng class chạy **isolated với thay đổi của IMP-04 vẫn present: 12/12 PASS trong 38 giây**, và login path (`PasswordLoginService`) không tham chiếu bất kỳ bean Customer nào → failure không liên quan IMP-04.
- `./gradlew build --max-workers=1 --console=plain` (lần 2, rerun sau khi môi trường nhàn rỗi): **BUILD SUCCESSFUL — 64 test classes / 853 tests / 0 failure / 0 error / 0 skipped** trong 14m 58s. Hóa giải: 824 baseline (IMP-03) + 17 test mới IMP-04 (7 unit + 6 PostgreSQL + 4 ArchUnit) + 12 test tsec của BE-ID-TEST-03 (đang PASS trong working tree); `IdentityAuthPerformanceTest` được tag `performance` nên loại khỏi `build` mặc định theo chỉnh sửa build.gradle.kts của BE-ID-TEST-03.

## F. Acceptance criteria

| Tiêu chí (task) | Trạng thái | Evidence |
|---|---|---|
| Resolve `userId → customerId` | PASS | Unit 7/7 + PostgreSQL 6/6 |
| Missing customer đúng nghĩa | PASS | `Optional.empty()`, không tự tạo row |
| Status handling đúng hợp đồng | PASS | INACTIVE trả snapshot + status; không invent eligibility (CUS-OI-06) |
| Không PII trong public DTO | PASS | Record 3 field, ArchUnit no-String-field, toString không PII |
| Không module cycle | PASS | ArchUnit slices + javadoc hướng dependency |
| Automated tests PASS | PASS | 7/7 unit, 6/6 integration, 9/9 arch, full build 853/853 (phần E) |
| Không lộ entity / không exposes internals | PASS | ArchUnit 2 rule mới; entity vẫn package-private trong `adapter.out` |

## G. Dependency và giới hạn còn mở

- `getCustomerView` (masked profile cho Account/Admin) chưa triển khai: CUS-OI-02 (mask fullName), CUS-OI-05 (scope + audit runtime) chưa được owner chốt — thuộc BE-CUS-IMP-06.
- El Customer không tự enforce eligibility khi status INACTIVE: chính sách tác động của INACTIVE lên Account/Transfer là CUS-OI-06, consumer tự quyết theo status snapshot.
- Identity wiring `AuthResponse.customerId`/`Session.customerId` thuộc owner Identity; Customer chỉ cung cấp hợp đồng này.
- Provisioning/create public API và status workflow vẫn BLOCKED theo DEC-CUS-002 (BE-CUS-IMP-09).

## Change Log

| Ngày | Mô tả |
|---|---|
| 17/11/2026 | Thêm `CustomerQuery`/`CustomerIdentity`/`Status`, port + adapter đọc tối thiểu, implementor application; 7 unit + 6 PostgreSQL + 4 ArchUnit rule PASS; full build 853/853 PASS (lần 1 fail do test song song BE-ID-TEST-03 flake tài nguyên, phân tích phần E) → DONE. |
