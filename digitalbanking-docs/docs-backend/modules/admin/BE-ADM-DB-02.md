# BE-ADM-DB-02 — Entity, repository và projection cho Admin approval

> **Mã task:** `BE-ADM-DB-02` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái:** **DONE (persistence)** — entity/store/lock `FOR UPDATE` đã có từ `BE-ADM-CON-01`; task này bổ sung read model list/detail có projection, filter, pagination, sort whitelist và test PostgreSQL/ArchUnit. Scope dữ liệu cho REST vẫn **BLOCKED** ở `BE-ADM-API-03` (`ADM-OI-06`).  
> **Nguồn ưu tiên:** `docs-common/04. Database design & analysis.md` §4.5.3 > `docs-common/07. API contracts.md` §2.5/§7.2.14/§8.8.3/§8.8.4 > `docs-common/design/05. Modules contract.md` §12.1/§12.1.1 > `docs-backend/admin/BE-ADM-CON-01..04`, `BE-ADM-DB-01` > `DEC-ADM-001..006`.

## 1. Kết luận

`BE-ADM-DB-02` hoàn tất phần **persistence** của Admin approval:

- Entity `ApprovalRequestEntity` map đủ 13 cột canonical `banking.approval_request`; không có cột `version`/optimistic lock (canonical dùng pessimistic row lock).
- Repository bổ sung query list bằng **constructor projection** (không đọc JSONB `payload`) và query detail bằng projection riêng; mọi filter dùng bind parameter.
- Read model `admin.application.query` cung cấp `ApprovalPage`, `ApprovalSummaryProjection`, `ApprovalDetailProjection`; không expose entity, không tái dùng REST DTO.
- Filter/pagination/sort theo `07` §8.8.3 và §2.5: `requestType`, `status`, `makerId`, `checkerId`, `from`/`to`, `page`/`size`, `sort`; sort whitelist chỉ `createdAt`; sort ngoài whitelist → `400 VALIDATION_ERROR`.
- Detail decode payload typed và bỏ `expectedVersion` trước khi trả projection (mask rule của `BE-ADM-CON-01/03`).
- Lock quyết định vẫn là `SELECT … FOR UPDATE` **chờ** trong transaction `MANDATORY`; không thay bằng `NOWAIT`/`SKIP LOCKED` vì contract concurrency của approval là “một thắng, request còn lại `409`” (`design/02` §7.9/§9.6). Xem §7.
- Read list/detail chạy trong `@Transactional(readOnly = true)` ở `ApprovalQueryService`.
- ArchUnit có 4 rule mới cho Admin; test PostgreSQL chứng minh filter/pagination/order/projection/read-only/lock timeout.

**Không thuộc phạm vi task này:** REST controller list/detail, actor/data-scope, `result` của approval đã apply, durable Audit. Các phần đó vẫn thuộc `BE-ADM-API-03`, `ADM-OI-06`, `ADM-OI-07`.

## 2. Entity persistence

### 2.1. Mapping canonical

`digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/out/ApprovalRequestEntity.java`

| Cột DB | Thuộc tính Java | Mapping | Ghi chú |
|---|---|---|---|
| `approval_id BIGINT IDENTITY PK` | `Long approvalId` | `@Id @GeneratedValue(IDENTITY)` | ID dạng `long`; wire `ID` string do API layer format |
| `request_type VARCHAR(32)` | `ApprovalRequestType` | `@Enumerated(STRING)`, `length=32`, not null | 4 giá trị canonical |
| `target_type VARCHAR(32)` | `ApprovalTargetType` | `@Enumerated(STRING)`, `length=32`, not null | 5 giá trị canonical |
| `target_id VARCHAR(64)` | `String targetId` | `length=64`, not null | 1–64 do DB CHECK |
| `payload JSONB` | `Map<String,Object>` | `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition=jsonb` | typed payload encode/decode qua `AdminPayloadJsonCodec` |
| `status VARCHAR(16)` | `ApprovalStatus` | `@Enumerated(STRING)`, `length=16`, not null | 5 status canonical |
| `maker_id BIGINT` | `long makerId` | not null, `updatable=false` | FK `app_user` |
| `checker_id BIGINT` | `Long checkerId` | nullable | FK `app_user` |
| `decision_note VARCHAR(500)` | `String decisionNote` | `length=500`, nullable | |
| `correlation_id VARCHAR(64)` | `String correlationId` | `length=64`, not null, `updatable=false` | trace ID |
| `created_at TIMESTAMPTZ` | `Instant createdAt` | not null, `updatable=false` | UTC instant |
| `decided_at TIMESTAMPTZ` | `Instant decidedAt` | nullable | bắt buộc với terminal decided status |
| `applied_at TIMESTAMPTZ` | `Instant appliedAt` | nullable | bắt buộc với `APPLIED` |

Entity là **package-private** trong `admin.adapter.out`; không có getter public, `toString()` trả `ApprovalRequestEntity[redacted]`. Không entity nào được dùng làm request/response DTO.

### 2.2. Versioning

Canonical `04` §4.5.3 **không có** cột `version`; entity **không** dùng `@Version`/optimistic locking. Bảo vệ đồng thời của approval là pessimistic row lock trên `approval_id` + re-check state trong cùng transaction (xem §7). Metamodel test xác nhận không có attribute `version`.

### 2.3. Quan hệ

Không khai báo `@ManyToOne`/`@OneToMany`; `maker_id`/`checker_id` là scalar ID để tránh eager loading, vòng lặp entity và phụ thuộc chéo module. FK do PostgreSQL bảo vệ (`ON DELETE/UPDATE RESTRICT`).

## 3. Repository và read adapter

### 3.1. `ApprovalRequestJpaRepository`

`admin/adapter/out/ApprovalRequestJpaRepository.java` — package-private Spring Data interface:

| Method | Kiểu | Mục đích |
|---|---|---|
| `findByIdForUpdate(long)` | `Optional<ApprovalRequestEntity>` + `@Lock(PESSIMISTIC_WRITE)` | `SELECT … FOR UPDATE` chờ cho decision/transition |
| `findSummaries(requestType, status, makerId, checkerId, from, to, Pageable)` | `Page<ApprovalSummaryProjection>` | list projection, không select `payload`; count query riêng |
| `findDetailRow(approvalId)` | `Optional<ApprovalDetailRow>` | detail projection gồm `payload` |

Tất cả filter là named parameter; không nối chuỗi client vào JPQL. Query dùng các cột/index canonical:

- `created_at` → `ix_approval_status_time` khi lọc status, và lọc khoảng thời gian.
- `request_type` → `ix_approval_type_status`.
- `maker_id`/`checker_id` → `ix_approval_maker`/`ix_approval_checker`.
- `target_type/target_id` không dùng làm filter API `API-APR-01`; index `ix_approval_target` giữ cho use case sau.

### 3.2. `JpaApprovalRequestReader`

`admin/adapter/out/JpaApprovalRequestReader.java` implement `ApprovalRequestReader`:

- List: dựng `Sort` **chỉ** từ `ApprovalSortField.persistenceProperty()` đã allowlist + tie-breaker `approvalId`; gọi `PageRequest.of(page, size, sort)`; trả `ApprovalPage` với `number/size/totalElements/totalPages` từ Spring Data.
- Detail: `ApprovalDetailRow` (record constructor projection) → `codec.decode` typed payload → kiểm tra `targetType/targetId` khớp payload → `codec.publicPayload` bỏ `expectedVersion` → `ApprovalDetailProjection`.
- Không mở transaction; transaction thuộc `ApprovalQueryService`.
- Không trả `payload` thô; lỗi payload không hợp lệ là `IllegalStateException` (dữ liệu canonical bị hỏng), không âm thầm trả dữ liệu sai.

### 3.3. `ApprovalRequestStore` (đã có)

`admin/application/port/ApprovalRequestStore` giữ nguyên:

- `insert`/`update`: `Propagation.MANDATORY` — ghi approval, provider change và C2 audit phải cùng commit.
- `lockById`: `Propagation.MANDATORY` + `PESSIMISTIC_WRITE`.
- `findById`: `readOnly = true`, dùng cho decision pre-check.

Không thêm method đọc entity vào store; read model mới tách riêng để list không load entity/payload.

## 4. Projection list/detail

| Type | Vai trò | Field |
|---|---|---|
| `ApprovalListQuery` | raw query input từ API layer (nullable = không gửi) | `requestType`, `status`, `makerId`, `checkerId`, `from`, `to`, `page`, `size`, `sort` |
| `ApprovalCriteria` | criteria đã validate/default, input của adapter | như trên + `ApprovalSort`; `from < to`, page/size bounds |
| `ApprovalSummaryProjection` | một dòng `ApprovalSummary` persistence | `approvalId`, `requestType`, `targetType`, `targetId`, `status`, `makerId`, `checkerId`, `createdAt`, `decidedAt`, `appliedAt`, `decisionNote`, `correlationId` |
| `ApprovalDetailProjection` | `ApprovalDetail` persistence | `summary`, `payload` đã mask |
| `ApprovalPage` | một trang offset | `items`, `number`, `size`, `totalElements`, `totalPages` |

Quy ước:

- `ApprovalSummaryProjection` **không** chứa `payload`; list query không đọc JSONB.
- Không chứa `ActorSummary`/`displayName`: `IdentityUserQuery.findStaffDisplayName` là read contract của Identity; resolve actor thuộc application/API layer để persistence không phụ thuộc Identity. `makerId`/`checkerId` là ID opaque, không phải PII.
- `ApprovalDetailProjection` chỉ chứa payload public đã bỏ `expectedVersion`; không chứa `result` vì canonical `approval_request` không có cột result và provider result chưa được persist (xem §10).
- Projection không phải REST DTO và không được trả trực tiếp từ controller; controller/API-03 sẽ map sang `ApprovalSummary`/`ApprovalDetail` + `PageMeta`/`asOf`.

## 5. Filter, pagination và sort

### 5.1. Filter

Theo `07` §8.8.3, `API-APR-01` nhận:

| Param | Kiểu | Quy tắc |
|---|---|---|
| `requestType` | enum | chỉ `LIMIT_CHANGE`, `ROLE_CHANGE` (giá trị retention bị `400`) |
| `status` | enum | 5 status canonical `PENDING/APPROVED/REJECTED/APPLIED/CANCELLED` |
| `makerId` | ID | dương; so khớp `maker_id` |
| `checkerId` | ID | dương; so khớp `checker_id`; `PENDING` không khớp |
| `from` | Instant | inclusive trên `created_at`; mặc định `now - 30 ngày` |
| `to` | Instant | exclusive trên `created_at`; mặc định `now` |

`from >= to` hoặc khoảng > 366 ngày → `400 VALIDATION_ERROR`. Khi client gửi `to` nhưng không gửi `from`, default `from = to - 30 ngày` (giữ đúng cửa sổ 30 ngày thay vì tạo khoảng vô nghĩa).

### 5.2. Pagination

- `page` 0-based, mặc định `0`, phải `>= 0`.
- `size` mặc định `20`, `1..100`; ngoài khoảng → `400 VALIDATION_ERROR`.
- `page` vượt `totalPages` → `items: []`, giữ `totalElements/totalPages`, không lỗi (`07` §2.5).
- Empty result → `totalElements=0`, `totalPages=0`, `items=[]`.
- `asOf` không nằm trong persistence projection; API layer dùng `Clock` khi dựng `PageResponse` để giữ cùng semantics `07` §7.2.5.

### 5.3. Sort whitelist

- Public key duy nhất: `createdAt` (`07` §2.5).
- Map sang JPA property `createdAt`; secondary order `approvalId` cùng chiều để pagination ổn định khi nhiều row cùng `created_at`.
- Default: `createdAt,desc` (mới nhất trước). `07` chỉ ghi field mặc định `createdAt`; chiều `desc` được chốt trong `DEC-ADM-006` và ghi lại tại `07` §2.5.
- Client gửi field ngoài whitelist, sai case, thiếu/sai direction, hoặc nhiều dấu phẩy → `400 VALIDATION_ERROR` trước khi chạm persistence.
- Sort value không bao giờ được nối vào JPQL/SQL; adapter chỉ dùng enum → property name.

## 6. Transaction boundaries

| Operation | Boundary | Ghi chú |
|---|---|---|
| `ApprovalQueryService.list/findDetail` | `@Transactional(readOnly = true)` | Service-level query boundary; adapter không mở transaction |
| `ApprovalRequestStore.findById` | `@Transactional(readOnly = true)` | decision pre-check, không khóa |
| `ApprovalRequestStore.insert/update/lockById` | `@Transactional(propagation = MANDATORY)` | phải join transaction của `ApprovalDecisionService`/`ApprovalProposalService` |
| `JpaApprovalRequestReader` | không annotation | chạy trong transaction read-only của service |

Không đặt `readOnly = true` trên method ghi và không đặt `Propagation.REQUIRES_NEW` cho approval. Test `listAndDetailRunInsideAnActiveReadOnlyTransaction` kiểm tra `TransactionSynchronizationManager` ngay tại lời gọi reader: transaction active + read-only = true.

## 7. Locking và concurrency

### 7.1. `FOR UPDATE` chờ (đã có, giữ nguyên)

`ApprovalRequestJpaRepository.findByIdForUpdate` dùng `@Lock(LockModeType.PESSIMISTIC_WRITE)`; `JpaApprovalRequestStore.lockById` là `MANDATORY`. `ApprovalDecisionService` gọi lock trong transaction quyết định, sau đó:

1. re-check `PENDING`,
2. kiểm maker ≠ checker/target,
3. consume step-up,
4. provider apply,
5. persist `APPLIED`/`REJECTED`,
6. C2 audit cuối transaction.

Hai checker đồng thời: một giữ row lock; checker còn lại **chờ**, sau commit thấy `APPLIED` và nhận `409 APPROVAL_ALREADY_DECIDED` — đúng `design/02` §7.9 (“một thắng, request còn lại `409`/retry”). Không mất update, không apply hai lần.

### 7.2. `NOWAIT`

`NOWAIT` **không** áp dụng cho approval row:

- `design/02` §7.3 chỉ yêu cầu `FOR UPDATE NOWAIT` cho **transfer row**; §7.9/§9.6 quy định approval dùng row lock chờ và loser nhận conflict.
- Nếu dùng `NOWAIT`, checker thứ hai sẽ nhận lỗi lock ngay (`55P03` → `503`) thay vì `409 APPROVAL_ALREADY_DECIDED`; như vậy sẽ đổi hành vi đã được test/duyệt và không phân biệt được “đang xử lý” với “đã quyết định”.
- Không dùng `SKIP LOCKED` cho approval decision (chỉ dùng cho job purge idempotency theo `V7`/`DEC-ADM-003`).

Nếu `lock_timeout` hết hạn (ví dụ statement timeout của môi trường), lỗi `SQLSTATE 55P03` **không bị nuốt**: nó lan ra `DataAccessException` và `DatabaseErrorMapper` map thành `503 SERVICE_UNAVAILABLE` ở HTTP boundary. Test mới giữ lock ở transaction khác, đặt `lock_timeout=100ms` và xác nhận `55P03`, sau đó row vẫn `PENDING`.

### 7.3. Không giữ lock quá lâu

Lock chỉ tồn tại trong transaction quyết định ngắn; không gọi external I/O ngoài các provider JOIN đã contract; C2 audit là bước cuối. Read list/detail không lấy lock.

## 8. Module boundary và ArchUnit

Đã thêm 4 rule trong `ModuleBoundaryArchTest`:

| Rule | Nội dung |
|---|---|
| `adminInternalsMustNotBeConsumedByOtherModules` | class ngoài `..banking.admin..` không được depend `admin.adapter/application/domain`; chỉ `admin.api` là contract |
| `adminInboundAdapterMustNotDependOnPersistenceModel` | `admin.adapter.in` không depend `jakarta.persistence`, `org.springframework.data`, `admin.adapter.out` |
| `adminDomainMustNotDependOnPersistenceOrAdapters` | aggregate `admin.domain` không depend JPA/Spring Data/adapter |
| `adminPublicApiMustNotExposePersistenceOrInternals` | `admin.api` (nếu mở) không depend persistence/application/domain |

Các rule generic sẵn có vẫn giữ: inbound adapter không gọi outbound trực tiếp; module không cycle; domain framework-free. Không nới rule nào để pass.

REST hiện tại (`RoleAdminController`, `LimitAdminController`) chỉ dùng DTO riêng + `AdminApprovalView` map; không trả entity/projection. DB-02 **không** thêm controller list/detail.

## 9. Kiểm chứng

### 9.1. Test mới/bổ sung

| File | Test | Nội dung |
|---|---|---|
| `admin/application/query/ApprovalQueryServiceTest` | 9 | default window/page/sort, filter passthrough, sort whitelist rejection, page/size bounds, time range, request type MVP, ID dương, detail ID |
| `admin/adapter/out/ApprovalRequestQueryPostgreSqlIntegrationTest` | 8 | filter đủ trường + half-open `from`/`to`, pagination + tie-breaker + page vượt cuối, empty page, list không decode JSONB, detail payload mask bỏ `expectedVersion`, decision facts, read-only transaction, invalid sort |
| `admin/adapter/out/ApprovalRequestPostgreSqlIntegrationTest` | +2 (10 tổng) | metamodel ID/enum/nullability/no version; lock timeout `55P03`, row vẫn `PENDING` |
| `architecture/ModuleBoundaryArchTest` | +4 rule (18 tổng) | boundary Admin |

Không dùng H2; mọi test lock/transaction dùng PostgreSQL 16 Testcontainers + Flyway thật.

### 9.2. Lệnh và kết quả

```bash
./gradlew test --tests 'com.simulatedbank.banking.admin.*'
# BUILD SUCCESSFUL — 14 classes / 116 tests / 0 failure / 0 error / 0 skip

./gradlew test --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest'
# BUILD SUCCESSFUL — 18 ArchUnit rules / 0 failure

./gradlew test --tests 'com.simulatedbank.banking.migration.*'
# BUILD SUCCESSFUL — 7 classes / 43 tests / 0 failure / 0 error / 0 skip

./gradlew cleanTest build
# BUILD SUCCESSFUL (7m47s) — 87 classes / 1055 tests / 0 failure / 0 error / 0 skip
```

`cleanTest build` chạy lại toàn bộ suite trên PostgreSQL 16 Testcontainers; số test tăng từ baseline `BE-ADM-DB-01` (1011/83) thêm 44 test / 4 class (9 unit query, 8 query PostgreSQL, 2 persistence lock/metamodel, 4 ArchUnit rule + các test Account V8 đang có trên branch). Không có test bị skip.

## 10. BLOCKED / còn mở

| Mục | Trạng thái | Owner/điều kiện mở |
|---|---|---|
| REST `GET /admin/approvals[/*]` | **BLOCKED** | `BE-ADM-API-03`; cần `APPROVAL_READ` + data-scope contract (`ADM-OI-06`); persistence đã sẵn sàng nhưng không được nối controller trước scope |
| Actor scope (maker/checker/Operations) | **BLOCKED** | `ADM-OI-06` (Admin/Security/BA/Customer/Account); filter `makerId/checkerId` **không** thay thế authorization |
| `ApprovalDetail.result` cho approval đã apply | **BLOCKED** | Canonical không có cột `result`; chưa có contract lưu provider result. Không suy diễn từ payload/idempotency response. |
| Mapping `ActorSummary.displayName` | **Đã có contract, chưa nối read model** | `identity.api.IdentityUserQuery.findStaffDisplayName`; API-03 sẽ resolve actor. DB-02 giữ persistence projection với ID để tránh N+1/cross-module read. |
| `NOWAIT` cho approval row | **Không áp dụng** | `design/02` §7.9/§9.6 yêu cầu waiting lock + `409`; xem §7.2. |
| `ADM-OI-03` partial unique PENDING | **OPEN ngoài canonical** | DB owner; nếu duyệt phải thêm migration mới, không sửa `V6` |

## 11. Acceptance criteria

- [x] Entity Admin approval map đúng `04` §4.5.3 (13 cột, PK/FK/enum/nullability).
- [x] Repository có query theo ID, business filters, pagination, deterministic sort.
- [x] Projection list/detail tách khỏi entity và REST DTO.
- [x] List không đọc/không decode JSONB payload; detail payload đã mask/bỏ `expectedVersion`.
- [x] Filter `requestType/status/makerId/checkerId/from/to` đúng contract, `from` inclusive/`to` exclusive.
- [x] Pagination 0-based, default 20, max 100, page vượt cuối trả empty.
- [x] Sort whitelist `createdAt` + `asc/desc`; giá trị khác → `400 VALIDATION_ERROR`.
- [x] `FOR UPDATE` chờ chạy trong transaction `MANDATORY`; lock timeout không bị nuốt.
- [x] List/detail dùng `@Transactional(readOnly = true)`.
- [x] ArchUnit Admin boundary pass, không nới rule cũ.
- [x] PostgreSQL integration/regression và full build pass.
- [x] Tài liệu + checklist cập nhật; không sửa module khác.

## 12. Trạng thái cuối

`BE-ADM-DB-02` — **DONE (persistence)**. Phần còn lại để mở REST list/detail là `BE-ADM-API-03` với scope contract `ADM-OI-06` và `result` persistence; đây là blocker của API-03, không phải lỗi persistence.
